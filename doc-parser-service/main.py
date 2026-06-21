"""
文档解析 FastAPI 服务

启动命令：
    python main.py
    # 或
    uvicorn main:app --host 0.0.0.0 --port 8000 --reload

API 文档：http://localhost:8000/docs
"""

from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional
from pathlib import Path
import parser as doc_parser

app = FastAPI(title='文档解析服务', version='1.0.0')

# CORS：允许前端直连
app.add_middleware(
    CORSMiddleware,
    allow_origins=['*'],
    allow_credentials=True,
    allow_methods=['*'],
    allow_headers=['*'],
)


# ==================== Pydantic 模型 ====================

class ChunkResponse(BaseModel):
    chunkIndex: int
    heading: str
    content: str
    charCount: int
    tokenCount: int
    chunkType: str


class ParseResult(BaseModel):
    fileName: str
    fileType: str
    totalChars: int
    chunkCount: int
    totalTokens: int
    chunks: List[ChunkResponse]


class BatchParseResult(BaseModel):
    totalFiles: int
    successCount: int
    failedCount: int
    results: List[ParseResult]
    errors: List[dict]


# ==================== 路由 ====================

@app.get('/health')
def health():
    """健康检查"""
    return {'status': 'ok', 'service': 'doc-parser'}


@app.post('/parse/file', response_model=ParseResult)
async def parse_single_file(file: UploadFile = File(...)):
    """
    解析单个文件

    支持格式：.md, .markdown, .txt, .pdf, .docx
    返回切分后的分片列表
    """
    if not file.filename:
        raise HTTPException(status_code=400, detail='文件名不能为空')

    content = await file.read()
    try:
        chunks = doc_parser.parse_bytes(content, file.filename)
    except ImportError as e:
        raise HTTPException(status_code=500, detail=f'缺少依赖: {e}')
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    return _build_parse_result(file.filename, chunks)


@app.post('/parse/batch', response_model=BatchParseResult)
async def parse_batch_files(files: List[UploadFile] = File(...)):
    """
    批量解析多个文件

    一次上传多个文件，逐个解析后返回汇总结果
    """
    results: List[ParseResult] = []
    errors: list = []
    success_count = 0

    for file in files:
        if not file.filename:
            errors.append({'fileName': 'unknown', 'error': '文件名不能为空'})
            continue

        content = await file.read()
        try:
            chunks = doc_parser.parse_bytes(content, file.filename)
            results.append(_build_parse_result(file.filename, chunks))
            success_count += 1
        except Exception as e:
            errors.append({'fileName': file.filename, 'error': str(e)})

    return BatchParseResult(
        totalFiles=len(files),
        successCount=success_count,
        failedCount=len(files) - success_count,
        results=results,
        errors=errors,
    )


@app.post('/parse/folder')
async def parse_folder(path: str, knowledge_base_id: Optional[str] = None):
    """
    解析指定文件夹下的所有文档

    参数：
        path: 文件夹路径（服务器本地路径）
        knowledge_base_id: 知识库ID（可选）

    返回：解析结果汇总
    """
    folder = Path(path)
    if not folder.exists():
        raise HTTPException(status_code=404, detail=f'文件夹不存在: {path}')
    if not folder.is_dir():
        raise HTTPException(status_code=400, detail=f'不是文件夹: {path}')

    # 支持的文件扩展名
    supported = {'.md', '.markdown', '.txt', '.pdf', '.docx'}
    files = [f for f in folder.iterdir() if f.suffix.lower() in supported and f.is_file()]

    if not files:
        raise HTTPException(status_code=404, detail='文件夹下没有支持的文档文件')

    results: List[ParseResult] = []
    errors: list = []
    success_count = 0

    for file in sorted(files):
        try:
            chunks = doc_parser.parse_file(str(file))
            results.append(_build_parse_result(file.name, chunks))
            success_count += 1
        except Exception as e:
            errors.append({'fileName': file.name, 'error': str(e)})

    return BatchParseResult(
        totalFiles=len(files),
        successCount=success_count,
        failedCount=len(files) - success_count,
        results=results,
        errors=errors,
    )


# ==================== 工具方法 ====================

def _build_parse_result(filename: str, chunks: list) -> ParseResult:
    """构建 ParseResult 响应"""
    total_chars = sum(c.char_count for c in chunks)
    total_tokens = sum(c.token_count for c in chunks)
    file_type = Path(filename).suffix.lower().lstrip('.')

    return ParseResult(
        fileName=filename,
        fileType=file_type,
        totalChars=total_chars,
        chunkCount=len(chunks),
        totalTokens=total_tokens,
        chunks=[ChunkResponse(**c.to_dict()) for c in chunks],
    )


# ==================== 启动入口 ====================

if __name__ == '__main__':
    import uvicorn
    print('文档解析服务启动中...')
    print('API 文档: http://localhost:8000/docs')
    uvicorn.run('main:app', host='0.0.0.0', port=8000, reload=True)
