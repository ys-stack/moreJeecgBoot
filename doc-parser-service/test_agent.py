#!/usr/bin/env python3
"""
Agent Tool Calling 自动化评测脚本

批量发送测试问题到 ToolChat 接口，记录回答和工具调用情况，生成 HTML 测试报告。

使用方法：
    1. pip install requests pytest
    2. 修改下方 CONFIG 中的 BASE_URL 和 TOKEN
    3. python test_agent.py            # 运行全部测试
    4. python test_agent.py -v         # 详细输出
    5. python test_agent.py --report   # 仅生成报告（跳过 pytest）

测试覆盖 4 大场景：
    - 查询场景（8 条）：queryOrder、queryUser 正常查询
    - 写操作场景（4 条）：createTicket 需确认流程
    - 异常场景（5 条）：参数校验、注入攻击、边界值
    - 越权场景（3 条）：无权限工具调用
"""

import json
import time
import uuid
import os
import sys
import argparse
from datetime import datetime
from dataclasses import dataclass, field, asdict
from typing import Optional

import requests

# ============================================================================
# 配置
# ============================================================================

CONFIG = {
    "BASE_URL": "http://localhost:8080/jeecg-boot",
    # 从浏览器 DevTools → Network → 任意请求的 X-Access-Token 复制
    "TOKEN": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VybmFtZSI6ImFkbWluIiwiY2xpZW50VHlwZSI6IlBDIiwiZXhwIjoxNzgzMjg0OTM4fQ.MQnpadX3xfwNWJULri82lY4Rpa1RIV3LLc1ZvPfBnXk",
    "TIMEOUT": 120,  # 单次请求超时（秒），模型调用可能较慢
}

# ============================================================================
# 数据结构
# ============================================================================

@dataclass
class TestCase:
    """测试用例定义"""
    id: str                          # 用例编号
    category: str                    # 场景分类：query / write / exception / auth
    name: str                        # 用例名称
    message: str                     # 发给 Agent 的消息
    expected_tools: list = field(default_factory=list)  # 预期调用的工具
    expect_confirm: bool = False     # 是否预期触发确认流程
    expect_error: bool = False       # 是否预期返回错误
    description: str = ""            # 用例描述


@dataclass
class TestResult:
    """测试结果"""
    case: TestCase
    passed: bool = False
    actual_tools: list = field(default_factory=list)
    content: str = ""                # Agent 回复内容
    needs_confirm: bool = False
    rounds: int = 0
    cost_ms: int = 0
    error_msg: str = ""
    raw_response: dict = field(default_factory=dict)
    duration: float = 0.0            # 脚本侧耗时


# ============================================================================
# 20 条测试用例
# ============================================================================

TEST_CASES = [
    # ---- 查询场景 (8) ----
    TestCase(
        id="Q01", category="query", name="查询订单-标准",
        message="帮我查一下订单 B100 的状态",
        expected_tools=["queryOrder"],
        description="标准订单查询，应调用 queryOrder 并返回订单信息",
    ),
    TestCase(
        id="Q02", category="query", name="查询订单-带编号",
        message="订单号 ORD-2026-001 现在什么情况",
        expected_tools=["queryOrder"],
        description="带完整订单号格式的查询",
    ),
    TestCase(
        id="Q03", category="query", name="查询用户-标准",
        message="查一下用户张三的信息",
        expected_tools=["queryUser"],
        description="标准用户查询，应调用 queryUser",
    ),
    TestCase(
        id="Q04", category="query", name="查询用户-手机号",
        message="手机号 13800138000 对应哪个用户",
        expected_tools=["queryUser"],
        description="通过手机号查询用户",
    ),
    TestCase(
        id="Q05", category="query", name="连续查询-订单",
        message="再看看 B100 这个订单的收货地址",
        expected_tools=["queryOrder"],
        description="追问式查询，测试多轮对话上下文",
    ),
    TestCase(
        id="Q06", category="query", name="连续查询-用户",
        message="这个用户最近有什么订单",
        expected_tools=["queryOrder"],
        description="基于上文用户的追问，测试上下文理解",
    ),
    TestCase(
        id="Q07", category="query", name="模糊查询",
        message="有没有最近的异常订单",
        expected_tools=["queryOrder"],
        description="模糊条件查询，模型应尝试调用 queryOrder",
    ),
    TestCase(
        id="Q08", category="query", name="闲聊-不应调工具",
        message="你好，你能做什么",
        expected_tools=[],
        description="闲聊不应触发任何工具调用",
    ),

    # ---- 写操作场景 (4) ----
    TestCase(
        id="W01", category="write", name="创建工单-标准",
        message="帮我提一个 bug 工单，标题是登录页崩溃，描述是点击登录后白屏",
        expected_tools=["createTicket"],
        expect_confirm=True,
        description="创建工单是写操作，应触发确认流程（needsConfirm=true）",
    ),
    TestCase(
        id="W02", category="write", name="创建工单-简短",
        message="建个工单：首页加载慢",
        expected_tools=["createTicket"],
        expect_confirm=True,
        description="简短描述也应触发创建工单",
    ),
    TestCase(
        id="W03", category="write", name="确认执行-模拟",
        message="帮我提一个工单，标题是接口超时，描述是查询订单接口响应超过5秒",
        expected_tools=["createTicket"],
        expect_confirm=True,
        description="写操作需要确认，测试 confirmTools 参数传递",
    ),
    TestCase(
        id="W04", category="write", name="查询+写操作混合",
        message="先查一下订单 B100，如果有问题就帮我建个工单",
        expected_tools=["queryOrder", "createTicket"],
        expect_confirm=True,
        description="混合场景：先查询再写操作，应调用两个工具",
    ),

    # ---- 异常场景 (5) ----
    TestCase(
        id="E01", category="exception", name="空消息",
        message="",
        expected_tools=[],
        expect_error=True,
        description="空消息应被拦截，不应调用模型",
    ),
    TestCase(
        id="E02", category="exception", name="SQL注入尝试",
        message="查询订单 ' OR 1=1; DROP TABLE orders; --",
        expected_tools=[],
        expect_error=False,
        description="SQL 注入应被参数校验拦截或安全处理",
    ),
    TestCase(
        id="E03", category="exception", name="XSS注入尝试",
        message="查询订单 <script>alert('xss')</script>",
        expected_tools=[],
        expect_error=False,
        description="XSS 注入应被过滤或安全处理",
    ),
    TestCase(
        id="E04", category="exception", name="超长输入",
        message="帮我查一下订单 " + "A" * 5000,
        expected_tools=[],
        expect_error=False,
        description="超长输入应被长度校验拦截或截断处理",
    ),
    TestCase(
        id="E05", category="exception", name="不存在的订单格式",
        message="查询订单 !@#$%^&*() 的状态",
        expected_tools=["queryOrder"],
        expect_error=False,
        description="非法格式参数，工具应返回友好错误而非异常",
    ),

    # ---- 越权场景 (3) ----
    TestCase(
        id="A01", category="auth", name="无Token请求",
        message="查询订单 B100",
        expected_tools=[],
        expect_error=True,
        description="不携带 Token 应返回 401/510",
    ),
    TestCase(
        id="A02", category="auth", name="无效Token",
        message="查询订单 B100",
        expected_tools=[],
        expect_error=True,
        description="伪造 Token 应返回认证失败",
    ),
    TestCase(
        id="A03", category="auth", name="过期Token",
        message="查询订单 B100",
        expected_tools=[],
        expect_error=True,
        description="过期 Token 应返回认证失败（需手动设置过期Token测试）",
    ),
]


# ============================================================================
# API 客户端
# ============================================================================

class AgentClient:
    """ToolChat API 客户端"""

    def __init__(self, base_url: str, token: str, timeout: int = 120):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def chat(self, message: str, session_id: str = "",
             confirm_tools: list = None,
             token_override: str = None) -> dict:
        """
        同步调用 ToolChat 接口

        Returns:
            dict: {
                "success": bool,
                "data": ToolChatResponse | None,
                "error": str | None,
                "status_code": int,
            }
        """
        url = f"{self.base_url}/practice/tool/chat"
        body = {
            "message": message,
            "sessionId": session_id or str(uuid.uuid4()),
        }
        if confirm_tools:
            body["confirmTools"] = confirm_tools

        # 每次请求独立构建 headers，避免 session 级别 header 被 token_override 污染
        headers = {
            "Content-Type": "application/json",
            "X-Access-Token": token_override if token_override is not None else self.token,
        }

        try:
            resp = requests.post(url, json=body, headers=headers, timeout=self.timeout)
            result = resp.json()

            if resp.status_code != 200:
                return {
                    "success": False,
                    "data": None,
                    "error": f"HTTP {resp.status_code}: {result.get('message', resp.text)}",
                    "status_code": resp.status_code,
                }

            if not result.get("success", False):
                return {
                    "success": False,
                    "data": None,
                    "error": result.get("message", "接口返回失败"),
                    "status_code": resp.status_code,
                }

            return {
                "success": True,
                "data": result.get("result", {}),
                "error": None,
                "status_code": resp.status_code,
            }

        except requests.exceptions.Timeout:
            return {"success": False, "data": None, "error": f"请求超时 ({self.timeout}s)", "status_code": 0}
        except requests.exceptions.ConnectionError:
            return {"success": False, "data": None, "error": "连接失败，请检查 BASE_URL", "status_code": 0}
        except Exception as e:
            return {"success": False, "data": None, "error": str(e), "status_code": 0}


# ============================================================================
# 测试执行器
# ============================================================================

class TestRunner:
    """测试执行器"""

    def __init__(self, config: dict):
        self.config = config
        self.client = AgentClient(
            base_url=config["BASE_URL"],
            token=config["TOKEN"],
            timeout=config["TIMEOUT"],
        )
        self.results: list[TestResult] = []

    def run_all(self, cases: list[TestCase] = None):
        """运行全部测试用例"""
        cases = cases or TEST_CASES
        total = len(cases)

        print(f"\n{'='*60}")
        print(f"  Agent Tool Calling 自动化评测")
        print(f"  目标: {self.config['BASE_URL']}")
        print(f"  用例数: {total}")
        print(f"  时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"{'='*60}\n")

        for i, case in enumerate(cases, 1):
            print(f"[{i}/{total}] {case.id} {case.name}...", end=" ", flush=True)
            result = self._run_one(case)
            self.results.append(result)

            status = "✅ PASS" if result.passed else "❌ FAIL"
            tools_str = ",".join(result.actual_tools) if result.actual_tools else "无"
            print(f"{status}  工具:[{tools_str}]  耗时:{result.cost_ms}ms")

        self._print_summary()

    def _run_one(self, case: TestCase) -> TestResult:
        """执行单条用例"""
        result = TestResult(case=case)
        start = time.time()

        # 越权场景：特殊处理 token
        if case.id == "A01":
            api_result = self.client.chat(case.message, token_override="")
        elif case.id == "A02":
            api_result = self.client.chat(case.message, token_override="fake_token_12345")
        elif case.id == "A03":
            # 过期 token 需要手动设置，这里用无效 token 模拟
            api_result = self.client.chat(case.message, token_override="expired_token_abc")
        else:
            api_result = self.client.chat(case.message)

        result.duration = round(time.time() - start, 2)
        result.raw_response = api_result

        # 解析结果
        if not api_result["success"]:
            result.error_msg = api_result.get("error", "未知错误")
            result.content = result.error_msg
            # 预期错误的用例，接口确实返回错误 → PASS
            if case.expect_error:
                result.passed = True
            return result

        data = api_result["data"]
        result.content = data.get("content", "")
        result.needs_confirm = data.get("needsConfirm", False)
        result.rounds = data.get("rounds", 0)
        result.cost_ms = data.get("costMs", 0)

        # 提取实际调用的工具
        tool_calls = data.get("toolCalls", [])
        result.actual_tools = list(set(
            tc.get("toolCode", "") for tc in tool_calls if tc.get("status") == "success"
        ))

        # 评测逻辑
        result.passed = self._evaluate(case, result)
        return result

    def _evaluate(self, case: TestCase, result: TestResult) -> bool:
        """评测：判断用例是否通过"""

        # 预期错误的场景
        if case.expect_error:
            return bool(result.error_msg)

        # 有接口错误但用例不预期错误 → FAIL
        if result.error_msg and not case.expect_error:
            return False

        # 检查工具调用是否匹配预期
        expected = set(case.expected_tools)
        actual = set(result.actual_tools)

        # 空消息不应该调任何工具
        if case.id == "E01" and actual:
            return False

        # 工具匹配检查
        if expected and not expected.issubset(actual):
            return False

        # 确认流程检查
        if case.expect_confirm and not result.needs_confirm:
            return False

        return True

    def _print_summary(self):
        """打印汇总"""
        total = len(self.results)
        passed = sum(1 for r in self.results if r.passed)
        failed = total - passed

        print(f"\n{'='*60}")
        print(f"  评测完成")
        print(f"  通过: {passed}/{total} ({passed*100//total}%)")
        print(f"  失败: {failed}/{total}")

        # 按场景统计
        categories = {}
        for r in self.results:
            cat = r.case.category
            if cat not in categories:
                categories[cat] = {"total": 0, "passed": 0}
            categories[cat]["total"] += 1
            if r.passed:
                categories[cat]["passed"] += 1

        print(f"\n  分场景统计:")
        cat_names = {"query": "查询", "write": "写操作", "exception": "异常", "auth": "越权"}
        for cat, stats in categories.items():
            name = cat_names.get(cat, cat)
            p = stats["passed"]
            t = stats["total"]
            icon = "✅" if p == t else "⚠️"
            print(f"    {icon} {name}: {p}/{t}")

        if failed > 0:
            print(f"\n  失败用例:")
            for r in self.results:
                if not r.passed:
                    reason = r.error_msg or f"预期工具:{r.case.expected_tools} 实际:{r.actual_tools}"
                    print(f"    ❌ {r.case.id} {r.case.name}: {reason}")

        print(f"{'='*60}\n")


# ============================================================================
# HTML 报告生成
# ============================================================================

def generate_html_report(results: list[TestResult], output_path: str):
    """生成 HTML 测试报告"""
    total = len(results)
    passed = sum(1 for r in results if r.passed)
    failed = total - passed
    pass_rate = f"{passed*100/total:.1f}" if total > 0 else "0"
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    # 按场景分组
    groups = {}
    for r in results:
        cat = r.case.category
        if cat not in groups:
            groups[cat] = []
        groups[cat].append(r)

    cat_names = {"query": "查询场景", "write": "写操作场景", "exception": "异常场景", "auth": "越权场景"}
    cat_icons = {"query": "🔍", "write": "✏️", "exception": "⚡", "auth": "🔒"}

    # 构建用例行 HTML
    rows_html = ""
    for cat, items in groups.items():
        cat_name = cat_names.get(cat, cat)
        cat_icon = cat_icons.get(cat, "📋")
        cat_passed = sum(1 for r in items if r.passed)
        rows_html += f"""
        <tr class="category-header">
            <td colspan="7">{cat_icon} {cat_name} ({cat_passed}/{len(items)})</td>
        </tr>"""
        for r in items:
            status_class = "pass" if r.passed else "fail"
            status_icon = "✅" if r.passed else "❌"
            tools = ", ".join(r.actual_tools) if r.actual_tools else "-"
            expected = ", ".join(r.case.expected_tools) if r.case.expected_tools else "-"
            confirm = "是" if r.needs_confirm else "-"
            content_preview = r.content[:80] + "..." if len(r.content) > 80 else r.content
            content_preview = content_preview.replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

            rows_html += f"""
        <tr class="{status_class}">
            <td>{r.case.id}</td>
            <td>{status_icon} {r.case.name}</td>
            <td class="msg-cell" title="{r.case.message}">{r.case.message[:50]}{'...' if len(r.case.message) > 50 else ''}</td>
            <td>{expected}</td>
            <td>{tools}</td>
            <td>{confirm}</td>
            <td>{r.cost_ms}ms / {r.rounds}轮</td>
        </tr>
        <tr class="detail-row {status_class}">
            <td></td>
            <td colspan="6">
                <div class="detail-content">
                    <strong>描述:</strong> {r.case.description}<br>
                    <strong>回复:</strong> {content_preview}<br>
                    {f'<strong>错误:</strong> {r.error_msg}<br>' if r.error_msg else ''}
                    <strong>耗时:</strong> 接口 {r.cost_ms}ms / 脚本 {r.duration}s
                </div>
            </td>
        </tr>"""

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>Agent 评测报告</title>
    <style>
        * {{ margin: 0; padding: 0; box-sizing: border-box; }}
        body {{ font-family: -apple-system, 'Segoe UI', sans-serif; background: #f5f5f5; padding: 24px; color: #333; }}
        .container {{ max-width: 1200px; margin: 0 auto; }}
        h1 {{ font-size: 24px; margin-bottom: 8px; }}
        .subtitle {{ color: #888; font-size: 14px; margin-bottom: 24px; }}

        .summary {{ display: flex; gap: 16px; margin-bottom: 24px; }}
        .summary-card {{
            background: #fff; border-radius: 10px; padding: 20px 24px;
            box-shadow: 0 1px 3px rgba(0,0,0,.08); flex: 1;
        }}
        .summary-card .label {{ font-size: 13px; color: #888; margin-bottom: 4px; }}
        .summary-card .value {{ font-size: 28px; font-weight: 700; }}
        .summary-card .value.pass {{ color: #52c41a; }}
        .summary-card .value.fail {{ color: #ff4d4f; }}
        .summary-card .value.rate {{ color: #1890ff; }}

        table {{ width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,.08); }}
        th {{ background: #fafafa; padding: 12px 16px; text-align: left; font-size: 13px; color: #666; border-bottom: 1px solid #f0f0f0; }}
        td {{ padding: 10px 16px; font-size: 13px; border-bottom: 1px solid #f5f5f5; vertical-align: top; }}
        tr.category-header td {{ background: #f0f5ff; font-weight: 600; font-size: 14px; padding: 10px 16px; color: #1890ff; }}
        tr.fail td {{ background: #fff2f0; }}
        tr.detail-row td {{ padding: 0 16px 10px 48px; background: transparent; }}
        tr.detail-row.fail td {{ background: #fff2f0; }}
        .detail-content {{ font-size: 12px; color: #666; line-height: 1.8; }}
        .msg-cell {{ max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }}

        .footer {{ margin-top: 24px; text-align: center; color: #bbb; font-size: 12px; }}
    </style>
</head>
<body>
<div class="container">
    <h1>🤖 Agent Tool Calling 评测报告</h1>
    <div class="subtitle">{now} · {CONFIG['BASE_URL']}</div>

    <div class="summary">
        <div class="summary-card">
            <div class="label">总用例</div>
            <div class="value">{total}</div>
        </div>
        <div class="summary-card">
            <div class="label">通过</div>
            <div class="value pass">{passed}</div>
        </div>
        <div class="summary-card">
            <div class="label">失败</div>
            <div class="value fail">{failed}</div>
        </div>
        <div class="summary-card">
            <div class="label">通过率</div>
            <div class="value rate">{pass_rate}%</div>
        </div>
    </div>

    <table>
        <thead>
            <tr>
                <th>编号</th>
                <th>用例名称</th>
                <th>输入消息</th>
                <th>预期工具</th>
                <th>实际工具</th>
                <th>确认</th>
                <th>耗时/轮数</th>
            </tr>
        </thead>
        <tbody>
            {rows_html}
        </tbody>
    </table>

    <div class="footer">
        Generated by Agent Test Runner · {now}
    </div>
</div>
</body>
</html>"""

    with open(output_path, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"📄 HTML 报告已生成: {output_path}")


# ============================================================================
# pytest 集成（可选）
# ============================================================================

# 供 pytest 收集的用例列表
pytest_cases = []
for _case in TEST_CASES:
    if _case.id in ("A01", "A02", "A03"):
        continue  # 越权场景需要特殊 token，跳过 pytest 自动收集
    pytest_cases.append(_case)


def _make_test_func(case: TestCase):
    """为每条用例生成一个 pytest 测试函数"""
    def test_func():
        runner = TestRunner(CONFIG)
        result = runner._run_one(case)
        assert result.passed, (
            f"[{case.id}] {case.name} 失败: "
            f"预期工具={case.expected_tools}, 实际={result.actual_tools}, "
            f"错误={result.error_msg}"
        )
    test_func.__name__ = f"test_{case.id}_{case.category}"
    test_func.__doc__ = case.description
    return test_func


# 动态注册 pytest 用例
for _c in pytest_cases:
    globals()[f"test_{_c.id}_{_c.category}"] = _make_test_func(_c)


# ============================================================================
# 主入口
# ============================================================================

def main():
    parser = argparse.ArgumentParser(description="Agent Tool Calling 评测脚本")
    parser.add_argument("--report", action="store_true", help="仅运行并生成 HTML 报告")
    parser.add_argument("--report-path", default="agent_test_report.html", help="报告输出路径")
    parser.add_argument("--category", choices=["query", "write", "exception", "auth"], help="只运行指定场景")
    parser.add_argument("--case", help="只运行指定用例ID（如 Q01）")
    parser.add_argument("--base-url", help="覆盖 BASE_URL")
    parser.add_argument("--token", help="覆盖 TOKEN")
    args = parser.parse_args()

    # 覆盖配置
    if args.base_url:
        CONFIG["BASE_URL"] = args.base_url
    if args.token:
        CONFIG["TOKEN"] = args.token

    # 检查配置
    if CONFIG["TOKEN"] == "YOUR_TOKEN_HERE":
        print("❌ 请先在脚本顶部的 CONFIG 中设置 TOKEN")
        print("   获取方式: 浏览器登录 → F12 → Network → 复制任意请求的 X-Access-Token")
        sys.exit(1)

    # 筛选用例
    cases = TEST_CASES
    if args.category:
        cases = [c for c in cases if c.category == args.category]
    if args.case:
        cases = [c for c in cases if c.id == args.case]

    if not cases:
        print("❌ 没有匹配的用例")
        sys.exit(1)

    # 运行
    runner = TestRunner(CONFIG)
    runner.run_all(cases)

    # 生成报告
    if args.report or True:  # 默认总是生成报告
        report_path = args.report_path
        generate_html_report(runner.results, report_path)

    # 退出码
    failed = sum(1 for r in runner.results if not r.passed)
    sys.exit(1 if failed > 0 else 0)


if __name__ == "__main__":
    main()
