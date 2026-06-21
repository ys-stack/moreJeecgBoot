# 文档解析服务

基于 FastAPI 的文档解析微服务，支持 Markdown、TXT、PDF、DOCX 格式的智能切分。

## 快速启动

```bash
# 1. 创建虚拟环境（你的 Python 是 embed 版，用 virtualenv）
D:\soft\Python312\python.exe -m virtualenv venv

# 2. 激活虚拟环境
venv\Scripts\activate

# 3. 安装依赖
pip install -r requirements.txt

# 4. 启动服务
python main.py
```

启动后访问 http://localhost:8000/docs 查看交互式 API 文档。

## API 接口

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/health` | 健康检查 |
| POST | `/parse/file` | 单文件解析（上传文件） |
| POST | `/parse/batch` | 批量文件解析（多文件上传） |
| POST | `/parse/folder?path=xxx` | 解析服务器本地文件夹 |

## Python 知识点总结

### 1. FastAPI 框架基础
- `@app.post/get` — 路由装饰器，声明 HTTP 方法和路径
- `async def` — 异步处理函数，适合 I/O 密集场景
- `UploadFile` — 文件上传参数类型，底层是 SpooledTemporaryFile

### 2. Pydantic 数据验证
- `BaseModel` — 定义请求/响应的数据模型
- 自动生成 JSON Schema 和 API 文档
- 类型校验：字段类型不匹配时自动返回 422

### 3. CORS 中间件
- `CORSMiddleware` — 允许前端跨域请求
- `allow_origins=['*']` — 开发环境允许所有来源
- 生产环境应限制为具体域名

### 4. 文件处理
- `UploadFile.read()` — 异步读取上传文件内容
- `Path.read_text()` — 同步读取本地文件
- `tempfile.NamedTemporaryFile` — 临时文件处理（PDF/DOCX 需要落盘）

### 5. 正则表达式
- `re.compile(pattern)` — 预编译正则，提升性能
- `re.split()` — 按正则切分文本
- `finditer()` — 迭代匹配，适合逐句处理

### 6. 状态机（标题路径栈）
- 用两个列表 `heading_levels` 和 `heading_texts` 模拟栈
- 遇到新标题时弹出同级及更低级别标题
- 构建面包屑式路径：`项目概述 > 背景 > 市场环境`

### 7. 三级降级切分策略
- 段落切分（空行分割）— 保持语义完整
- 句子切分（。！？.!?）— 段落仍超长时
- 硬切（固定字数）— 兜底策略

### 8. PDF 解析
- `pdfplumber` 库：基于 pdfminer，提取文本和表格
- `page.extract_text()` — 按页提取文本

### 9. DOCX 解析
- `python-docx` 库：读写 Word 文档
- `Document.paragraphs` — 获取所有段落

### 10. 虚拟环境
- `virtualenv` — 创建隔离的 Python 环境
- `venv\Scripts\activate` — Windows 激活
- `deactivate` — 退出虚拟环境
