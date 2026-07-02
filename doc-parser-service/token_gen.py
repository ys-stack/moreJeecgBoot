#!/usr/bin/env python3
"""
JeecgBoot Token 生成工具

自动处理验证码流程，登录拿到 JWT Token，方便测试使用。

使用方法：
    python token_gen.py                          # 交互式输入用户名密码
    python token_gen.py -u admin -p 123456       # 直接传入用户名密码
    python token_gen.py --no-captcha             # 服务端已关闭验证码时使用
    python token_gen.py --copy                   # 自动复制 token 到剪贴板（需 pyperclip）

也可在 yml 中关闭验证码：
    jeecg:
      firewall:
        enableLoginCaptcha: false
"""

import argparse
import base64
import json
import os
import platform
import subprocess
import sys
import tempfile
import time

import requests

# ============================================================================
# 配置（按需修改）
# ============================================================================

BASE_URL = "http://localhost:8080/jeecg-boot"

# 默认账号（可通过命令行参数覆盖）
DEFAULT_USERNAME = "admin"
DEFAULT_PASSWORD = "123456"


def get_captcha(check_key: str, session: requests.Session) -> str:
    """获取验证码图片，保存到临时文件并打开，返回文件路径"""
    url = f"{BASE_URL}/sys/randomImage/{check_key}"
    resp = session.get(url, timeout=10)
    resp.raise_for_status()

    data = resp.json()
    if not data.get("success"):
        print(f"[ERROR] 获取验证码失败: {data.get('message')}")
        sys.exit(1)

    # 返回的是 base64 编码的图片（data:image/png;base64,...）
    base64_str = data["result"]
    if "," in base64_str:
        base64_str = base64_str.split(",", 1)[1]

    img_bytes = base64.b64decode(base64_str)

    # 保存到临时文件
    tmp_path = os.path.join(tempfile.gettempdir(), f"jeecg_captcha_{check_key}.png")
    with open(tmp_path, "wb") as f:
        f.write(img_bytes)

    # 自动打开图片
    open_image(tmp_path)
    return tmp_path


def open_image(path: str):
    """跨平台打开图片文件"""
    system = platform.system()
    try:
        if system == "Windows":
            os.startfile(path)
        elif system == "Darwin":
            subprocess.run(["open", path], check=False)
        else:
            subprocess.run(["xdg-open", path], check=False)
    except Exception:
        pass  # 打开失败不影响流程，用户可以去临时目录找


def login(username: str, password: str, captcha: str = None, check_key: str = None) -> str:
    """登录并返回 token"""
    url = f"{BASE_URL}/sys/login"

    body = {
        "username": username,
        "password": password,
    }

    if captcha is not None and check_key is not None:
        body["captcha"] = captcha
        body["checkKey"] = check_key

    session = requests.Session()
    resp = session.post(url, json=body, timeout=15)
    resp.raise_for_status()

    data = resp.json()
    if not data.get("success"):
        msg = data.get("message", "未知错误")
        print(f"[ERROR] 登录失败: {msg}")
        sys.exit(1)

    token = data["result"]["token"]
    user_info = data["result"].get("userInfo", {})
    real_name = user_info.get("realname", username)

    print()
    print("=" * 60)
    print(f"  登录成功!")
    print(f"  用户: {real_name} ({username})")
    print(f"  Token:")
    print(f"  {token}")
    print("=" * 60)
    print()

    # 尝试复制到剪贴板
    copy_to_clipboard(token)

    return token


def copy_to_clipboard(text: str):
    """尝试复制文本到系统剪贴板"""
    system = platform.system()
    try:
        if system == "Windows":
            process = subprocess.Popen(["clip"], stdin=subprocess.PIPE)
            process.communicate(text.encode("utf-16-le"))
            print("[OK] Token 已复制到剪贴板")
        elif system == "Darwin":
            process = subprocess.Popen(["pbcopy"], stdin=subprocess.PIPE)
            process.communicate(text.encode("utf-8"))
            print("[OK] Token 已复制到剪贴板")
        else:
            # Linux: 尝试 xclip 或 xsel
            for cmd in ["xclip -selection clipboard", "xsel --clipboard --input"]:
                try:
                    process = subprocess.Popen(cmd.split(), stdin=subprocess.PIPE)
                    process.communicate(text.encode("utf-8"))
                    print(f"[OK] Token 已复制到剪贴板 (via {cmd.split()[0]})")
                    return
                except FileNotFoundError:
                    continue
            print("[TIP] 未找到 xclip/xsel，请手动复制 Token")
    except Exception:
        print("[TIP] 剪贴板复制失败，请手动复制 Token")


def main():
    parser = argparse.ArgumentParser(description="JeecgBoot Token 生成工具")
    parser.add_argument("-u", "--username", default=None, help="用户名（默认 admin）")
    parser.add_argument("-p", "--password", default=None, help="密码（默认 123456）")
    parser.add_argument("--base-url", default=None, help="后端地址（默认 http://localhost:8080/jeecg-boot）")
    parser.add_argument("--no-captcha", action="store_true", help="跳过验证码（需服务端关闭验证码校验）")
    parser.add_argument("--copy", action="store_true", help="自动复制到剪贴板（默认已启用）")
    args = parser.parse_args()

    global BASE_URL
    if args.base_url:
        BASE_URL = args.base_url.rstrip("/")

    # 获取用户名密码
    username = args.username or input(f"用户名 [{DEFAULT_USERNAME}]: ").strip() or DEFAULT_USERNAME
    password = args.password or input(f"密码 [{DEFAULT_PASSWORD}]: ").strip() or DEFAULT_PASSWORD

    print(f"\n正在登录 {BASE_URL} ...")

    if args.no_captcha:
        # 无验证码模式
        login(username, password)
    else:
        # 验证码模式
        check_key = str(int(time.time() * 1000))

        session = requests.Session()
        captcha_path = get_captcha(check_key, session)

        print(f"[INFO] 验证码图片已打开: {captcha_path}")
        captcha_code = input("请输入验证码: ").strip()

        if not captcha_code:
            print("[ERROR] 验证码不能为空")
            sys.exit(1)

        login(username, password, captcha=captcha_code, check_key=check_key)


if __name__ == "__main__":
    main()
