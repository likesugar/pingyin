#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成内置发音音节库。

覆盖范围 = 《现代汉语常用字表》3500 字的全部读音（1110 个带调音节）
           ∪ 训练语料里出现的音节（含轻声等，约 60 个）
为每个音节挑一个读音可靠的代表字，调用微软神经语音合成成 mp3，
裁掉首尾静音后按 40kbps 单声道存进 assets/syllables/。
App 端按汉字 -> 音节的对应关系依次播放这些音频，实现完全离线的发音。
"""
import asyncio
import json
import os
import subprocess
import sys
import unicodedata

import edge_tts
from pypinyin import pinyin, Style

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT_DIR = os.path.join(ASSETS, "syllables")
TMP_DIR = "/tmp/syl_raw"

VOICE = "zh-CN-XiaoxiaoNeural"
RATE = "-12%"          # 稍慢一点，便于跟读
BITRATE = "40k"
CONCURRENCY = 6
RETRY = 3


def tone3(syllable: str) -> str:
    """带调拼音 -> ASCII 文件名：hǎo -> hao3、lǜ -> lv4、de -> de5"""
    nfd = unicodedata.normalize("NFD", syllable)
    tone = 5
    out = []
    for ch in nfd:
        o = ord(ch)
        if o == 0x0308:            # 分音符：把前面的 u 变成 v
            if out and out[-1] == "u":
                out[-1] = "v"
            continue
        if o == 0x0304:
            tone = 1
            continue
        if o == 0x0301:
            tone = 2
            continue
        if o == 0x030C:
            tone = 3
            continue
        if o == 0x0300:
            tone = 4
            continue
        if o >= 0x300:
            continue
        out.append(ch)
    return "".join(out).lower() + str(tone)


def common_chars():
    """3500 常用字表：字 \\t 拼音 \\t 声母 \\t 韵母，按常用度排序"""
    rows = []
    with open(os.path.join(ASSETS, "common3500.txt"), encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 2 and p[0] and p[1]:
                rows.append((p[0], p[1]))
    return rows


def collect():
    """返回 {音节: 代表字}；音节取 3500 常用字 ∪ 语料

    代表字按可信度分三层取，前一层取不到才降级：
      1. 3500 常用字表（字与拼音一一对应，最可靠）
      2. 语料（文本与注音逐字符对齐后配对，覆盖轻声等表外音节）
      3. 全字典 pinyin.txt（兜底生僻音节）
    """
    need = set(py for _, py in common_chars())

    corpus = json.load(open(os.path.join(ASSETS, "corpus.json"), encoding="utf-8"))
    from_corpus = {}
    for g in corpus["groups"]:
        for lesson in g["lessons"]:
            for seg in lesson["segments"]:
                toks = seg["p"].split(" ")
                # t 与 p 逐字符对齐（t 里的空格也占 p 的一位），不能先去掉空格再配对
                for ch, p in zip(seg["t"], toks):
                    if ch == " " or not p:
                        continue
                    need.add(p)
                    lst = from_corpus.setdefault(p, [])
                    if ch not in lst:
                        lst.append(ch)

    from_common = {}
    for ch, py in common_chars():
        from_common.setdefault(py, []).append(ch)

    from_dict = {}
    with open(os.path.join(ASSETS, "pinyin.txt"), encoding="utf-8") as f:
        for line in f:
            ch, _, py = line.rstrip("\n").partition("\t")
            if ch and py and py in need:
                from_dict.setdefault(py, []).append(ch)

    picked = {}
    for sy in sorted(need):
        rep = None
        for tier in (from_common, from_corpus, from_dict):
            rep = pick_rep(sy, tier.get(sy) or [])
            if rep:
                break
        if not rep and is_neutral(sy):
            rep = pick_neutral(sy, (from_corpus, from_common, from_dict))
        if rep:
            picked[sy] = rep
    missing = sorted(need - set(picked))
    if missing:
        print("no rep char for:", missing[:10], file=sys.stderr)
    return picked


def is_neutral(sy):
    """轻声：整串没有声调符号"""
    nfd = unicodedata.normalize("NFD", sy)
    return all(not (0x0300 <= ord(c) <= 0x036F) for c in nfd)


def plain(sy):
    """去调拼音（ü 记作 v），用于轻声的字形比对"""
    nfd = unicodedata.normalize("NFD", sy.replace("ü", "v").replace("Ü", "V"))
    return "".join(c for c in nfd if ord(c) < 0x300).lower()


def pick_neutral(sy, tiers):
    """轻声音节没有对应的单字读音，用「去调后同音」的字读本调"""
    base = plain(sy)
    for tier in tiers:
        for ch in (tier.get(sy) or [])[:20]:
            if plain(pinyin(ch, style=Style.TONE)[0][0]) == base:
                return ch
    return None


def pick_rep(sy, chars):
    """从候选字里挑最能读准该音的：优先单音字，其次多音字里含该音的。
    候选里一个都没有该读音时返回 None，交给下一层候选。"""
    fallback = None            # 多音字，但确实含该读音
    for ch in chars[:40]:
        hetero = pinyin(ch, style=Style.TONE, heteronym=True)[0]
        if sy in hetero:
            if len(hetero) == 1:
                return ch
            if fallback is None:
                fallback = ch
    return fallback


async def synth_one(sem, text, path):
    async with sem:
        for attempt in range(RETRY):
            try:
                await edge_tts.Communicate(text, VOICE, rate=RATE).save(path)
                if os.path.getsize(path) > 800:
                    return True
            except Exception as e:  # noqa: BLE001
                if attempt == RETRY - 1:
                    print("  fail", text, e, file=sys.stderr)
            await asyncio.sleep(0.6 * (attempt + 1))
    return False


def trim(src, dst):
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-af",
         "silenceremove=start_periods=1:start_duration=0:start_threshold=-45dB:detection=peak,"
         "areverse,"
         "silenceremove=start_periods=1:start_duration=0:start_threshold=-45dB:detection=peak,"
         "areverse",
         "-ar", "22050", "-ac", "1", "-b:a", BITRATE, dst],
        check=True,
    )


async def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(TMP_DIR, exist_ok=True)
    picked = collect()
    print("syllables:", len(picked), flush=True)
    sem = asyncio.Semaphore(CONCURRENCY)
    todo = []
    for sy, ch in sorted(picked.items()):
        name = tone3(sy) + ".mp3"
        dst = os.path.join(OUT_DIR, name)
        if os.path.exists(dst) and os.path.getsize(dst) > 800:
            continue
        todo.append((sy, ch, name))
    print("to synth:", len(todo), flush=True)

    async def job(sy, ch, name):
        raw = os.path.join(TMP_DIR, name)
        ok = await synth_one(sem, ch, raw)
        if ok:
            trim(raw, os.path.join(OUT_DIR, name))

    await asyncio.gather(*[job(sy, ch, name) for sy, ch, name in todo])

    # 索引：音节(tab)文件名
    lines = []
    for sy in sorted(picked):
        name = tone3(sy) + ".mp3"
        if os.path.exists(os.path.join(OUT_DIR, name)):
            lines.append("%s\t%s" % (sy, name))
    with open(os.path.join(ASSETS, "syllables.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    total = sum(os.path.getsize(os.path.join(OUT_DIR, n)) for _, n in
                ((l.split("\t")[0], l.split("\t")[1]) for l in lines))
    print("index rows:", len(lines), "audio total: %.2f MB" % (total / 1048576))

    # 覆盖率自检：3500 常用字的每个音节是否都有音频
    idx = set(l.split("\t")[0] for l in lines)
    need = set(py for _, py in common_chars())
    miss = sorted(need - idx)
    print("3500 chars syllables: %d, missing: %d %s" % (len(need), len(miss), miss[:10]))


if __name__ == "__main__":
    asyncio.run(main())
