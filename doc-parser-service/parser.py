"""
文档解析核心模块

支持的文档格式：
- Markdown (.md/.markdown): 按 ## 标题切分 + 标题路径栈 + 三级降级
- 纯文本 (.txt): 段落 → 句子 → 硬切的降级策略
- PDF (.pdf): pdfplumber 提取文本 → 同 TXT 策略
- Word (.docx): python-docx 提取段落 → 同 TXT 策略

切分参数与 Java 端 MarkdownParser 保持一致：
- MAX_CHUNK_SIZE = 500（单片最大字符数）
- HARD_SPLIT_SIZE = 200（硬切兜底字数）
"""

import re
import math
from dataclasses import dataclass, field
from typing import List
from pathlib import Path

# 切分常量
MAX_CHUNK_SIZE = 500
HARD_SPLIT_SIZE = 200

# 正则
HEADING_PATTERN = re.compile(r'^(#{1,6})\s+(.+)$')
SENTENCE_END = re.compile(r'[。！？.!?]')


@dataclass
class Chunk:
    """单个文档分片"""
    chunk_index: int = 0
    heading: str = ''
    content: str = ''
    char_count: int = 0
    token_count: int = 0
    chunk_type: str = 'text'

    def to_dict(self) -> dict:
        return {
            'chunkIndex': self.chunk_index,
            'heading': self.heading,
            'content': self.content,
            'charCount': self.char_count,
            'tokenCount': self.token_count,
            'chunkType': self.chunk_type,
        }


def estimate_tokens(text: str) -> int:
    """预估 Token 数：charCount / 1.5，向上取整"""
    return math.ceil(len(text) / 1.5)


# ==================== 主入口 ====================

def parse_file(file_path: str) -> List[Chunk]:
    """根据文件扩展名自动选择解析器"""
    path = Path(file_path)
    suffix = path.suffix.lower()

    if suffix in ('.md', '.markdown'):
        text = path.read_text(encoding='utf-8')
        return parse_markdown(text)
    elif suffix == '.txt':
        text = path.read_text(encoding='utf-8')
        return parse_plain_text(text)
    elif suffix == '.pdf':
        text = extract_pdf_text(file_path)
        return parse_plain_text(text)
    elif suffix == '.docx':
        text = extract_docx_text(file_path)
        return parse_plain_text(text)
    else:
        raise ValueError(f'不支持的文件格式: {suffix}')


def parse_bytes(content: bytes, filename: str) -> List[Chunk]:
    """从字节流解析（用于上传场景）"""
    suffix = Path(filename).suffix.lower()

    if suffix in ('.md', '.markdown'):
        text = content.decode('utf-8')
        return parse_markdown(text)
    elif suffix == '.txt':
        text = content.decode('utf-8')
        return parse_plain_text(text)
    elif suffix == '.pdf':
        import tempfile
        with tempfile.NamedTemporaryFile(suffix='.pdf', delete=False) as f:
            f.write(content)
            f.flush()
            text = extract_pdf_text(f.name)
        return parse_plain_text(text)
    elif suffix == '.docx':
        import tempfile
        with tempfile.NamedTemporaryFile(suffix='.docx', delete=False) as f:
            f.write(content)
            f.flush()
            text = extract_docx_text(f.name)
        return parse_plain_text(text)
    else:
        raise ValueError(f'不支持的文件格式: {suffix}')


# ==================== Markdown 解析 ====================

def parse_markdown(markdown: str) -> List[Chunk]:
    """
    Markdown 解析器（与 Java 端 MarkdownParser 逻辑一致）

    切分策略：
    1. 以 ## 标题为主切分点
    2. 标题路径栈追踪层级上下文
    3. 三级降级：段落 → 句子 → 硬切
    """
    if not markdown or not markdown.strip():
        return []

    chunks: List[Chunk] = []
    lines = markdown.split('\n')

    # 标题路径栈
    heading_levels: List[int] = []
    heading_texts: List[str] = []

    section_content: List[str] = []
    current_heading: str = ''
    current_chunk_type: str = 'text'

    for line in lines:
        match = HEADING_PATTERN.match(line.strip())

        if match:
            level = len(match.group(1))
            title = match.group(2).strip()

            # 弹出同级和更低级别的标题
            while heading_levels and heading_levels[-1] >= level:
                heading_levels.pop()
                heading_texts.pop()
            heading_levels.append(level)
            heading_texts.append(title)

            # ## 是主切分点：遇到新的 ## 时，先刷出前一个 section
            if level <= 2 and section_content:
                add_chunks(chunks, current_heading, '\n'.join(section_content), current_chunk_type)
                section_content.clear()
                current_chunk_type = 'text'

            # 更新当前标题路径
            current_heading = build_heading_path(heading_levels, heading_texts)

            # 标题行也写入内容
            if section_content:
                section_content.append('')
            section_content.append(line)
        else:
            # 普通内容行
            if section_content:
                section_content.append('')
            section_content.append(line)

            # 代码块检测
            if line.strip().startswith('```'):
                current_chunk_type = 'text' if current_chunk_type == 'code' else 'code'

    # 刷出最后一个 section
    if section_content:
        add_chunks(chunks, current_heading, '\n'.join(section_content), current_chunk_type)

    # 重新编号
    for i, chunk in enumerate(chunks):
        chunk.chunk_index = i

    return chunks


# ==================== 纯文本解析 ====================

def parse_plain_text(text: str) -> List[Chunk]:
    """纯文本解析：段落 → 句子 → 硬切"""
    if not text or not text.strip():
        return []

    chunks: List[Chunk] = []
    add_chunks(chunks, '', text, 'text')

    for i, chunk in enumerate(chunks):
        chunk.chunk_index = i

    return chunks


# ==================== PDF / DOCX 提取 ====================

def extract_pdf_text(file_path: str) -> str:
    """用 pdfplumber 提取 PDF 文本"""
    try:
        import pdfplumber
    except ImportError:
        raise ImportError('需要安装 pdfplumber: pip install pdfplumber')

    text_parts = []
    with pdfplumber.open(file_path) as pdf:
        for page in pdf.pages:
            page_text = page.extract_text()
            if page_text:
                text_parts.append(page_text)
    return '\n\n'.join(text_parts)


def extract_docx_text(file_path: str) -> str:
    """用 python-docx 提取 Word 文档文本"""
    try:
        from docx import Document
    except ImportError:
        raise ImportError('需要安装 python-docx: pip install python-docx')

    doc = Document(file_path)
    paragraphs = [p.text for p in doc.paragraphs if p.text.strip()]
    return '\n\n'.join(paragraphs)


# ==================== 内部工具方法 ====================

def build_heading_path(levels: List[int], texts: List[str]) -> str:
    """构建标题路径：项目概述 > 背景 > 市场环境"""
    return ' > '.join(texts)


def add_chunks(chunks: List[Chunk], heading: str, content: str, chunk_type: str):
    """将一个 section 加入分片列表，超长自动降级切分"""
    trimmed = content.strip()
    if not trimmed:
        return

    if len(trimmed) <= MAX_CHUNK_SIZE:
        chunks.append(_build_chunk(heading, trimmed, chunk_type))
    else:
        # 三级降级
        parts = _split_by_paragraphs(trimmed)
        for part in parts:
            if len(part) <= MAX_CHUNK_SIZE:
                chunks.append(_build_chunk(heading, part, chunk_type))
            else:
                sentences = _split_by_sentences(part)
                for sentence in sentences:
                    if len(sentence) <= MAX_CHUNK_SIZE:
                        chunks.append(_build_chunk(heading, sentence, chunk_type))
                    else:
                        # 兜底硬切
                        for hard in _force_split(sentence, HARD_SPLIT_SIZE):
                            chunks.append(_build_chunk(heading, hard, chunk_type))


def _split_by_paragraphs(content: str) -> List[str]:
    """按段落边界切分（空行分割），贪心合并"""
    paragraphs = re.split(r'\n\s*\n', content)
    result: List[str] = []
    current: List[str] = []
    current_len = 0

    for para in paragraphs:
        trimmed = para.strip()
        if not trimmed:
            continue

        if current and current_len + len(trimmed) + 2 > MAX_CHUNK_SIZE:
            result.append('\n\n'.join(current))
            current.clear()
            current_len = 0

        if current:
            current_len += 2  # \n\n
        current.append(trimmed)
        current_len += len(trimmed)

    if current:
        result.append('\n\n'.join(current))

    return result


def _split_by_sentences(content: str) -> List[str]:
    """按句子边界切分（。！？.!?），贪心合并"""
    result: List[str] = []
    current: List[str] = []
    current_len = 0
    last_end = 0

    for match in SENTENCE_END.finditer(content):
        end = match.end()
        sentence = content[last_end:end]
        last_end = end

        if current_len + len(sentence) > MAX_CHUNK_SIZE and current:
            result.append(''.join(current))
            current.clear()
            current_len = 0

        current.append(sentence)
        current_len += len(sentence)

    # 剩余内容
    if last_end < len(content):
        current.append(content[last_end:])

    if current:
        result.append(''.join(current))

    return result


def _force_split(content: str, max_size: int) -> List[str]:
    """硬切：按固定长度切分"""
    return [content[i:i + max_size] for i in range(0, len(content), max_size)]


def _build_chunk(heading: str, content: str, chunk_type: str) -> Chunk:
    """构建 Chunk 对象"""
    return Chunk(
        heading=heading,
        content=content,
        char_count=len(content),
        token_count=estimate_tokens(content),
        chunk_type=chunk_type,
    )
