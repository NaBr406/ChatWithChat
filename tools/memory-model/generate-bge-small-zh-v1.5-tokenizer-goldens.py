#!/usr/bin/env python3
"""Generate deterministic fixtures from the checksum-verified local tokenizer.

Reproduction environment:
    python -m pip install transformers==4.57.6 tokenizers==0.22.2
    python tools/memory-model/generate-bge-small-zh-v1.5-tokenizer-goldens.py
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import tokenizers
import transformers
from transformers import BertTokenizerFast


TRANSFORMERS_VERSION = "4.57.6"
TOKENIZERS_VERSION = "0.22.2"
TOKENIZER_ID = "BAAI/bge-small-zh-v1.5"
TOKENIZER_REVISION = "7999e1d3359715c523056ef9478215996d62a620"
VOCAB_SHA256 = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"
TOKENIZER_JSON_SHA256 = "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26"
TOKENIZER_CONFIG_SHA256 = "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a"
DEFAULT_MAX_TOKENS = 256


CASES: list[tuple[str, str, int]] = [
    ("empty", "", 256),
    ("ascii_whitespace_only", " \t\r\n  ", 256),
    ("simple_chinese", "你好", 256),
    ("query_prefix", "为这个句子生成表示以用于检索相关文章：", 256),
    ("chinese_memory_sentence", "用户正在开发本地向量记忆检索功能。", 256),
    ("mixed_chinese_english", "在 Android 15 上验证 ObjectBox memory index", 256),
    ("uppercase_is_preserved", "ChatWithChat ONNX Runtime API", 256),
    ("lowercase_english", "hybrid lexical fallback remains enabled", 256),
    ("ascii_punctuation", "hello,world! (test): yes/no?", 256),
    ("cjk_punctuation", "记忆：新增、更新；删除？可以！", 256),
    ("emoji_unknown", "我喜欢咖啡☕和编程🧑‍💻", 256),
    ("precomposed_accent", "Café déjà vu", 256),
    ("combining_accent_nfc", "Cafe\u0301 de\u0301ja\u0300", 256),
    ("tabs_newlines", "first\tsecond\n第三行\rfourth", 256),
    ("nul_control_removed", "mem\u0000ory\u0007index", 256),
    ("replacement_character_removed", "vector\ufffdstore", 256),
    ("fullwidth_text", "ＡＢＣ１２３，记忆", 256),
    ("integers", "版本 15 维度 512 最大 256 tokens", 256),
    ("date_and_decimal", "2026-07-13 score=0.875", 256),
    ("url", "https://huggingface.co/BAAI/bge-small-zh-v1.5", 256),
    ("email", "owner@example.com", 256),
    ("markdown_heading", "## Long-term Memory", 256),
    ("markdown_bullet", "- preference: 永久保留 lexical fallback", 256),
    ("kotlin_code", "val inputIds = longArrayOf(101, 872, 1962, 102)", 256),
    ("windows_path", "E:\\code\\ChatWithChat\\MEMORY.md", 256),
    ("literal_cls", "[CLS] hello", 256),
    ("adjacent_mask", "a[MASK]b", 256),
    ("literal_unknown", "[UNK]", 256),
    ("literal_pad", "before[PAD]after", 256),
    ("literal_sep", "first[SEP]second", 256),
    ("japanese", "東京でメモリ検索を使う", 256),
    ("korean", "서울에서 메모리 검색", 256),
    ("traditional_chinese", "使用者偏好離線記憶搜尋", 256),
    ("cjk_extension_a", "扩展汉字㐀测试", 256),
    ("cjk_extension_b", "扩展汉字𠀀测试", 256),
    ("arabic", "ذاكرة محلية للبحث", 256),
    ("cyrillic", "локальная память", 256),
    ("greek", "τοπική μνήμη", 256),
    ("devanagari", "स्थानीय स्मृति", 256),
    ("thai", "หน่วยความจำในเครื่อง", 256),
    ("hebrew", "זיכרון מקומי", 256),
    ("french", "mémoire locale vérifiée", 256),
    ("german", "lokaler Gedächtnisindex", 256),
    ("spanish_punctuation", "¿memoria local? ¡sí!", 256),
    ("english_contraction", "don't overwrite user's memory", 256),
    ("ascii_symbol_punctuation", "snake_case^value`quoted`~done", 256),
    ("long_wordpiece", "uncharacteristically tokenized retrieval", 256),
    ("word_over_100_characters", "a" * 101, 256),
    ("short_truncation", "向量记忆检索必须保留词法回退", 8),
    ("minimal_content_truncation", "任意正文都会被截断", 3),
    ("production_limit_truncation", "记忆" * 300, 256),
    ("unicode_line_separators", "first\u2028second\u2029third", 256),
    ("non_breaking_spaces", "one\u00a0two\u2007three\u202ffour", 256),
    ("private_use_removed", "vec\ue000tor", 256),
    ("math_symbols", "cosine(x, y) ≥ 0.95 ± ε", 256),
    ("currency_symbols", "预算 $12.50、€9、¥80", 256),
    ("html", "<strong>memory</strong>&amp;vector", 256),
    ("markdown_metadata", "<!-- memory:id=mem_001 type=preference -->", 256),
    ("food_query", "用户平时喜欢吃什么？", 256),
    ("preference_statement", "用户明确要求不要使用云端 embedding。", 256),
    ("daily_memory_text", "今天临时调试了一个错误，不应进入普通召回。", 256),
    ("stale_vector_text", "内容哈希不一致时向量分支必须 fail closed。", 256),
    ("deleted_content_text", "删除的记忆必须立即从查询结果失效。", 256),
    ("repeated_spaces", "hybrid     search   keeps lexical", 256),
    ("crlf", "line one\r\nline two", 256),
    ("decomposed_hangul_nfc", "한글 메모리", 256),
    ("zero_width_format_removed", "hybrid\u200blexical\u200dfallback", 256),
    ("variation_selector", "文本✈️检索", 256),
    ("all_literal_special_tokens", "[PAD][UNK][CLS][SEP][MASK]", 256),
    ("dense_chinese", "模型缺失损坏哈希不符保持未配置状态", 256),
    ("json_like_text", '{"strategy":"HYBRID","topK":8}', 256),
    ("long_url_query", "https://example.com/search?q=本地向量记忆&limit=20#result", 32),
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_artifacts(artifact_dir: Path) -> None:
    expected = {
        "vocab.txt": VOCAB_SHA256,
        "tokenizer.json": TOKENIZER_JSON_SHA256,
        "tokenizer_config.json": TOKENIZER_CONFIG_SHA256,
    }
    for relative_path, expected_hash in expected.items():
        path = artifact_dir / relative_path
        if not path.is_file():
            raise FileNotFoundError(f"Missing pinned tokenizer artifact: {path}")
        actual_hash = sha256(path)
        if actual_hash != expected_hash:
            raise RuntimeError(
                f"Pinned tokenizer artifact hash mismatch for {relative_path}: {actual_hash}"
            )


def generate(artifact_dir: Path) -> dict[str, object]:
    if transformers.__version__ != TRANSFORMERS_VERSION:
        raise RuntimeError(
            f"Expected transformers {TRANSFORMERS_VERSION}, found {transformers.__version__}"
        )
    if tokenizers.__version__ != TOKENIZERS_VERSION:
        raise RuntimeError(f"Expected tokenizers {TOKENIZERS_VERSION}, found {tokenizers.__version__}")
    verify_artifacts(artifact_dir)

    fast = BertTokenizerFast.from_pretrained(artifact_dir, local_files_only=True)
    fixtures: list[dict[str, object]] = []
    required_tokens = {"[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"}

    for name, text, max_tokens in CASES:
        options = {
            "add_special_tokens": True,
            "max_length": max_tokens,
            "truncation": True,
            "padding": False,
            "return_attention_mask": True,
            "return_token_type_ids": True,
        }
        encoding = fast(text, **options)
        tokens = fast.convert_ids_to_tokens(encoding["input_ids"])
        required_tokens.update(tokens)
        fixtures.append(
            {
                "name": name,
                "text": text,
                "maxTokens": max_tokens,
                "tokens": tokens,
                "inputIds": encoding["input_ids"],
                "attentionMask": encoding["attention_mask"],
                "tokenTypeIds": encoding["token_type_ids"],
            }
        )

    vocabulary = fast.get_vocab()
    required_vocabulary = {
        token: vocabulary[token]
        for token in sorted(required_tokens, key=lambda token: (vocabulary[token], token))
    }
    return {
        "metadata": {
            "generator": "tools/memory-model/generate-bge-small-zh-v1.5-tokenizer-goldens.py",
            "transformersVersion": TRANSFORMERS_VERSION,
            "tokenizersVersion": TOKENIZERS_VERSION,
            "canonicalTokenizerClass": "BertTokenizerFast",
            "tokenizerId": TOKENIZER_ID,
            "tokenizerRevision": TOKENIZER_REVISION,
            "vocabSha256": VOCAB_SHA256,
            "tokenizerJsonSha256": TOKENIZER_JSON_SHA256,
            "tokenizerConfigSha256": TOKENIZER_CONFIG_SHA256,
            "defaultMaxTokens": DEFAULT_MAX_TOKENS,
            "fixtureCount": len(fixtures),
        },
        "requiredVocabulary": required_vocabulary,
        "fixtures": fixtures,
    }


def main() -> None:
    project_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--artifact-dir",
        type=Path,
        default=project_root / "app/src/main/assets/memory-model/bge-small-zh-v1.5",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=(
            project_root
            / "app/src/test/resources/memory-model/bge-small-zh-v1.5-tokenizer-goldens.json"
        ),
    )
    args = parser.parse_args()

    payload = generate(args.artifact_dir.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {payload['metadata']['fixtureCount']} fixtures to {args.output}")


if __name__ == "__main__":
    main()
