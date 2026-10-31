#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 App 资源：
1. assets/pinyin.txt   —— 汉字->带调拼音字典（CJK 基本区），用于自定义文本标注
2. assets/corpus.json  —— 训练素材（已用 pypinyin 预标注拼音）
"""

import json
import os
from pypinyin import pinyin, Style

OUT_ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
os.makedirs(OUT_ASSETS, exist_ok=True)

# ---------------------------------------------------------------- 高频字表（按声母组织）
SHENGMU = {
    "b": "不把白百班般半办帮包报被本比边变表别病并部步八爸饱北备背吧",
    "p": "朋片平票品破跑盘旁怕排派碰皮篇便拼评苹普朋",
    "m": "没们每美门忙吗买卖面民名明母木目马妈米慢满毛帽么梦秘",
    "f": "发法反饭方房放飞非分份风服父复富夫妇福",
    "d": "大打带代待单但当到道得等地点电店定动都读度短段断对多弟冬懂",
    "t": "他她它太谈汤糖桃特提题体天田听停通同头图团推腿",
    "n": "那拿哪内能你年鸟您牛农女暖南难",
    "l": "来老乐累冷里理力立连脸练两亮林零领流六路旅绿拉辣篮浪",
    "g": "改干感刚高告歌给更工公共狗够瓜挂关观管光广规贵国果过哥各跟",
    "k": "开看康考靠科可课口哭苦快块宽况困",
    "h": "还孩海好喝河和黑很红后湖花话坏欢换黄回会婚活火或户互护华",
    "j": "机鸡极急几己家加间见件江将交叫教接街节结姐解今金近进京经九就局举句觉决",
    "q": "七期齐其起气千前钱亲青轻清情请秋求取全确却桥巧球区",
    "x": "西息希洗喜系下先现线相想向象小笑些心新信星行形休学雪寻习细夏",
    "zh": "之只知直值止中种众重周主住注助专转准桌着真正整政这章找照站展战张",
    "ch": "茶查差常场车城吃持出初除处川船窗床春长厂超程",
    "sh": "上少水睡说时石食使始是事手首书树双顺数师室试市实识世",
    "r": "然让热人认日如入若仍荣软",
    "z": "子字自走足组最在怎增总做作坐左杂脏再",
    "c": "才采菜参草层村存错次词聪从",
    "s": "三送诉算岁所随虽思死素速",
    "y": "呀言眼阳样要也业页一医衣已以意因音银应英影用优由有又右于鱼雨语元园远月云运养",
    "w": "外完玩晚万王网忘为位文问我无五午物务温闻",
    "零声母": "啊爱安按暗岸案而二儿耳恩偶欧",
}

FREQ_CHARS = []
for _sm, _chars in SHENGMU.items():
    for _c in _chars:
        if _c not in FREQ_CHARS:
            FREQ_CHARS.append(_c)

# ---------------------------------------------------------------- 素材
WORDS = [
    "时间", "生活", "工作", "学习", "朋友", "家人", "老师", "同学", "学校", "教室",
    "电脑", "手机", "网络", "信息", "新闻", "音乐", "电影", "图书", "报纸", "杂志",
    "今天", "明天", "昨天", "早上", "中午", "晚上", "现在", "以后", "以前", "经常",
    "美丽", "快乐", "幸福", "健康", "平安", "安静", "热闹", "方便", "简单", "复杂",
    "天气", "春天", "夏天", "秋天", "冬天", "阳光", "月亮", "星星", "天空", "大海",
    "山川", "河流", "森林", "草原", "城市", "乡村", "街道", "公园", "花园", "田野",
    "米饭", "面条", "包子", "饺子", "水果", "蔬菜", "牛奶", "咖啡", "茶水", "面包",
    "汽车", "火车", "飞机", "轮船", "地铁", "公交", "出租", "车站", "机场", "码头",
    "医生", "护士", "病人", "医院", "药品", "健康", "运动", "跑步", "游泳", "篮球",
    "思想", "精神", "文化", "历史", "科学", "技术", "教育", "艺术", "经济", "政治",
    "语言", "文字", "拼音", "汉字", "词语", "句子", "文章", "故事", "小说", "诗歌",
    "父母", "孩子", "兄弟", "姐妹", "爷爷", "奶奶", "外公", "外婆", "叔叔", "阿姨",
    "认真", "仔细", "努力", "坚持", "勇敢", "善良", "诚实", "谦虚", "热情", "耐心",
    "开始", "结束", "继续", "停止", "准备", "完成", "成功", "失败", "希望", "梦想",
    "喜欢", "讨厌", "高兴", "难过", "生气", "害怕", "担心", "放心", "明白", "知道",
    "说话", "听话", "看见", "听见", "想起", "忘记", "认识", "了解", "相信", "怀疑",
    "山水", "风景", "旅游", "旅行", "照片", "相机", "纪念", "礼物", "生日", "节日",
    "经验", "能力", "机会", "挑战", "问题", "答案", "方法", "结果", "原因", "过程",
    "安全", "危险", "保护", "帮助", "支持", "合作", "团结", "交流", "沟通", "理解",
]

IDIOMS = [
    "一心一意", "三心二意", "四面八方", "五湖四海", "七上八下", "九牛一毛", "十全十美",
    "百发百中", "千军万马", "万紫千红", "人山人海", "天长地久", "日月如梭", "光阴似箭",
    "春暖花开", "秋高气爽", "风和日丽", "山清水秀", "鸟语花香", "花红柳绿",
    "自相矛盾", "守株待兔", "刻舟求剑", "画蛇添足", "亡羊补牢", "掩耳盗铃",
    "望梅止渴", "胸有成竹", "熟能生巧", "水滴石穿", "铁杵成针", "愚公移山",
    "名副其实", "实事求是", "精益求精", "锦上添花", "雪中送炭", "助人为乐",
    "见义勇为", "拾金不昧", "尊师重道", "勤学苦练", "温故知新", "举一反三",
    "循序渐进", "融会贯通", "博古通今", "学以致用", "业精于勤", "孜孜不倦",
    "心花怒放", "兴高采烈", "眉开眼笑", "喜出望外", "欢天喜地", "载歌载舞",
    "目不转睛", "全神贯注", "聚精会神", "专心致志", "废寝忘食", "夜以继日",
]

SENTENCES = [
    "今天天气很好我们出去走走吧",
    "学习拼音是打字的基础",
    "他每天早上六点起床跑步",
    "这本书的内容非常有趣",
    "请把窗户关上外面风很大",
    "我们一起去看电影好不好",
    "妈妈做的饭菜总是那么香",
    "老师告诉我们做事要认真",
    "春天来了公园里的花都开了",
    "他用了三年时间学会了弹钢琴",
    "互联网让世界变得更小了",
    "坚持锻炼身体才会更健康",
    "这道题的答案需要仔细思考",
    "下班以后我常常去图书馆看书",
    "小狗在草地上快乐地奔跑",
    "雨水滋润着田里的庄稼",
    "年轻人应该有远大的理想",
    "遇到问题要想办法解决",
    "爷爷年轻的时候去过很多地方",
    "妹妹今年上小学一年级了",
    "这家餐厅的招牌菜很好吃",
    "火车准时到达了终点站",
    "夜空中的星星闪闪发亮",
    "他写字又快又好看",
    "我们要珍惜每一分每一秒",
    "朋友之间应该互相帮助",
    "奶奶的手艺是村里最好的",
    "这个小男孩非常聪明",
    "夏天的傍晚最适合散步",
    "只要有信心就没有做不到的事",
    "他用拼音输入法打字很快",
    "熟能生巧多练习就会进步",
    "写字要注意笔画的顺序",
    "良好的习惯从小养成",
    "教室里传来了朗朗的读书声",
    "科技的发展改变了生活方式",
    "小鸟在树上唱着动听的歌",
    "大海的胸怀宽广而深沉",
    "勤奋是通往成功的桥梁",
    "知识就像海洋永远学不完",
]

PARAGRAPHS = [
    "春天来了天气渐渐暖和了 树枝上长出了嫩绿的叶子 花儿一朵接一朵地开放 小鸟在枝头欢快地歌唱 田野里农民伯伯开始播种 一切都充满了生机和希望",
    "学习是一件需要耐心的事情 每天进步一点点 时间长了就会有大的收获 遇到困难不要轻易放弃 多想一想多试几次 办法总比问题多",
    "城市里的早晨总是很热闹 人们匆匆忙忙地赶路 公交车一辆接一辆地开过 街边的早餐店飘出香味 新的一天就这样开始了",
    "汉字是中华文化的瑰宝 每一个字都有它的故事 拼音帮助我们认识汉字的读音 打字让我们与世界更快交流 学好拼音是掌握信息时代的第一步",
    "夜晚的乡村格外安静 月亮挂在树梢上 星星铺满了天空 偶尔传来几声虫鸣 微风拂过稻田带来阵阵清香 让人感到无比放松",
]

# ---------------------------------------------------------------- 大规模语料
# 由 tools/build_extra_corpus.py 从公开词库（jieba 词频）、成语库与 Tatoeba 句库
# 筛选生成；文件缺失时回退到上面的手工小样本，保证脚本随时可运行。
_TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))


def _load_extra(name, fallback):
    path = os.path.join(_TOOLS_DIR, name)
    if not os.path.exists(path):
        return fallback
    items, seen = [], set()
    with open(path, encoding="utf-8") as f:
        for raw in f:
            s = raw.strip()
            if s and s not in seen:
                seen.add(s)
                items.append(s)
    return items or fallback


WORDS = _load_extra("extra_words.txt", WORDS)
IDIOMS = _load_extra("extra_idioms.txt", IDIOMS)
SENTENCES = _load_extra("extra_sentences.txt", SENTENCES)
PARAGRAPHS = _load_extra("extra_paragraphs.txt", PARAGRAPHS)

ZHENGTI = {
    "zhi": "知直只织之制治止志纸",
    "chi": "吃迟持池尺赤翅斥痴",
    "shi": "是时十石使事市师试识室世始食",
    "ri": "日",
    "zi": "字自子资紫滋姿",
    "ci": "词次此刺磁慈",
    "si": "四死思私丝司",
    "yi": "一以已意医衣宜义疑",
    "wu": "五无物务午武屋误悟",
    "yu": "于与语鱼雨玉遇育预",
    "ye": "也业夜叶页爷野液",
    "yue": "月约越阅跃岳",
    "yuan": "元园远院愿圆原源",
    "yin": "因音银引印饮阴",
    "yun": "云运允孕韵晕",
    "ying": "应英影迎营硬赢鹰",
}

CONFUSE = {
    "zh / z 平舌与翘舌": ["知识", "姿势", "主力", "阻力", "志愿", "自愿", "摘花", "栽花", "札记", "杂记"],
    "ch / c 平舌与翘舌": ["木柴", "木材", "推迟", "推辞", "鱼翅", "鱼刺", "重来", "从来", "初次", "此次"],
    "sh / s 平舌与翘舌": ["诗人", "私人", "商业", "桑叶", "收集", "搜集", "闪光", "散光", "午睡", "五岁"],
    "n / l 鼻音与边音": ["水牛", "水流", "无奈", "无赖", "年代", "连带", "脑子", "老子", "女客", "旅客"],
    "f / h 唇齿与舌根": ["开发", "开花", "公费", "工会", "奋战", "混战", "防空", "航空", "幅度", "弧度"],
    "r / l 翘舌与边音": ["入口", "路口", "天然", "天蓝", "热天", "乐天", "柔软", "揉面"],
    "an / ang 前鼻与后鼻": ["开饭", "开放", "反问", "访问", "山口", "伤口", "一班", "一帮", "担心", "当心"],
    "en / eng 前鼻与后鼻": ["真诚", "征程", "陈旧", "成就", "吩咐", "丰富", "盆子", "棚子", "人参", "人生"],
    "in / ing 前鼻与后鼻": ["人民", "人名", "亲近", "清静", "心情", "行星", "信服", "幸福", "临时", "零食"],
    "uan / uang 前鼻与后鼻": ["机关", "激光", "转弯", "装完", "船员", "床沿", "罐头", "光头"],
}

FINALS_ORDER = [
    "a", "o", "e", "i", "u", "v", "ai", "ei", "ao", "ou",
    "an", "en", "ang", "eng", "ong", "er",
    "ia", "ie", "iao", "iu", "ian", "in", "iang", "ing", "iong",
    "ua", "uo", "uai", "ui", "uan", "un", "uang", "ueng",
    "ve", "van", "vn",
]

FINALS_DISPLAY = {
    "v": "ü", "ve": "üe", "van": "üan", "vn": "ün",
    "ong": "ong", "iong": "iong",
}

# 常用字表里缺失的韵母，手工补充例字
EXTRA_FINALS = {
    "o": "波坡摸佛婆泼播默博",
    "uo": "多说国火果坐左过活",
    "uang": "光黄双王装窗床望",
    "iong": "用兄穷雄胸勇拥",
    "uai": "快坏帅外拐歪",
    "un": "论村准春轮寸",
    "ueng": "翁",
}

# 轻声 / 多音词组覆盖，保证提示读音贴近普通话习惯
PHRASE_OVERRIDE = {
    "知识": "zhī shi", "认识": "rèn shi", "见识": "jiàn shi", "时候": "shí hou",
    "朋友": "péng you", "麻烦": "má fan", "什么": "shén me", "明白": "míng bai",
    "衣服": "yī fu", "东西": "dōng xi", "头发": "tóu fa", "告诉": "gào su",
    "休息": "xiū xi", "眼睛": "yǎn jing", "喜欢": "xǐ huan", "太阳": "tài yang",
    "月亮": "yuè liang", "消息": "xiāo xi", "关系": "guān xi", "客气": "kè qi",
    "漂亮": "piào liang", "便宜": "pián yi", "商量": "shāng liang", "钥匙": "yào shi",
    "师傅": "shī fu", "糊涂": "hú tu", "萝卜": "luó bo", "葡萄": "pú tao",
    "爸爸": "bà ba", "妈妈": "mā ma", "谢谢": "xiè xie", "星星": "xīng xing",
    "地方": "dì fang", "事情": "shì qing", "行李": "xíng li", "清楚": "qīng chu",
    "一阵": "yī zhèn", "一个": "yī gè", "一定": "yī dìng", "一起": "yī qǐ",
}


def tone_of(chars, per_char=False):
    """返回与 chars 一一对应的带调拼音列表"""
    if per_char:
        out = []
        for c in chars:
            if c.strip() == "":
                out.append("")
                continue
            out.append(pinyin(c, style=Style.TONE)[0][0])
        return out
    # pypinyin 会把空格原样返回，这里统一替换为空串，保证「字符数 == 音节数」
    return ["" if p[0].strip() == "" else p[0] for p in pinyin(chars, style=Style.TONE)]


def annotate(text):
    """词组优先标注：命中覆盖表用手工读音，其余连续汉字整段交给 pypinyin"""
    result = []
    buf = []

    def flush():
        if buf:
            result.extend(tone_of("".join(buf)))
            buf.clear()

    i = 0
    while i < len(text):
        hit = None
        for w, py in PHRASE_OVERRIDE.items():
            if text.startswith(w, i):
                hit = (w, py)
                break
        if hit:
            flush()
            result.extend(hit[1].split(" "))
            i += len(hit[0])
            continue
        if text[i] == " ":
            flush()
            result.append("")
        else:
            buf.append(text[i])
        i += 1
    flush()
    return " ".join(result)


def make_seg(text, per_char=False):
    if per_char:
        return {"t": text, "p": " ".join(tone_of(text, per_char=True))}
    return {"t": text, "p": annotate(text)}


def chunk(lst, n):
    for i in range(0, len(lst), n):
        yield lst[i:i + n]


def build(cjk_chars=None):
    groups = []

    # ---- 拼音基础：声母
    sm_lessons = []
    for sm, chars in SHENGMU.items():
        if sm == "零声母":
            title = "零声母（a o e 起头）"
        else:
            title = "声母 %s" % sm
        sm_lessons.append({
            "title": title,
            "segments": [make_seg("".join(chars), per_char=True)],
        })
    groups.append({"id": "shengmu", "name": "声母", "desc": "23 个声母专项", "lessons": sm_lessons})

    # ---- 拼音基础：韵母
    finals_map = {}
    for c in FREQ_CHARS:
        fin = _strip_tone(pinyin(c, style=Style.FINALS_TONE)[0][0])
        finals_map.setdefault(fin, [])
        finals_map[fin].append(c)
    # 字量不足时用手工挑选的常用字补齐（避免拉入生僻字）
    for fin, extra in EXTRA_FINALS.items():
        bucket = finals_map.setdefault(fin, [])
        for c in extra:
            if c not in bucket:
                bucket.append(c)
    fin_lessons = []
    for fin in FINALS_ORDER:
        candidates = finals_map.get(fin, [])
        if len(candidates) < 3:
            continue
        chars = "".join(candidates[:14])
        label = FINALS_DISPLAY.get(fin, fin)
        fin_lessons.append({
            "title": "韵母 %s" % label,
            "segments": [make_seg(chars, per_char=True)],
        })
    groups.append({"id": "yunmu", "name": "韵母", "desc": "24 个韵母专项", "lessons": fin_lessons})

    # ---- 拼音基础：整体认读
    zt_lessons = []
    for syl, chars in ZHENGTI.items():
        zt_lessons.append({
            "title": "整体认读 %s" % syl,
            "segments": [make_seg("".join(chars), per_char=True)],
        })
    groups.append({"id": "zhengren", "name": "整体认读", "desc": "16 个整体认读音节",
                   "lessons": zt_lessons})

    # ---- 拼音基础：易混淆
    cf_lessons = []
    for name, words in CONFUSE.items():
        cf_lessons.append({
            "title": "辨音 %s" % name,
            "segments": [make_seg(" ".join(words[i:i + 2])) for i in range(0, len(words), 2)],
        })
    groups.append({"id": "bianyin", "name": "易混淆音", "desc": "平翘舌、前后鼻音对比训练",
                   "lessons": cf_lessons})

    # ---- 常用单字
    zi_lessons = []
    for idx, part in enumerate(chunk(FREQ_CHARS, 50), 1):
        zi_lessons.append({
            "title": "常用单字 第 %d 组" % idx,
            "segments": [make_seg("".join(part), per_char=True)],
        })
    groups.append({"id": "zi", "name": "常用单字", "desc": "高频汉字 %d 个，换字池 3500 字" % len(FREQ_CHARS),
                   "lessons": zi_lessons})

    # ---- 双字词语
    ci_lessons = []
    for idx, part in enumerate(chunk(WORDS, 50), 1):
        ci_lessons.append({
            "title": "常用词语 第 %d 组" % idx,
            "segments": [make_seg(" ".join(part))],
        })
    groups.append({"id": "ci", "name": "常用词语", "desc": "常用词语 %d 个" % len(WORDS),
                   "lessons": ci_lessons})

    # ---- 成语
    cy_lessons = []
    for idx, part in enumerate(chunk(IDIOMS, 50), 1):
        cy_lessons.append({
            "title": "成语 第 %d 组" % idx,
            "segments": [make_seg(" ".join(part))],
        })
    groups.append({"id": "chengyu", "name": "成语", "desc": "常用成语 %d 条" % len(IDIOMS),
                   "lessons": cy_lessons})

    # ---- 短句
    ju_lessons = []
    for idx, part in enumerate(chunk(SENTENCES, 20), 1):
        ju_lessons.append({
            "title": "短句 第 %d 组" % idx,
            "segments": [make_seg(s) for s in part],
        })
    groups.append({"id": "duanju", "name": "短句", "desc": "常用短句 %d 句" % len(SENTENCES),
                   "lessons": ju_lessons})

    # ---- 段落
    duan_lessons = []
    for idx, part in enumerate(chunk(PARAGRAPHS, 8), 1):
        segs = []
        for item in part:
            if " " in item:
                segs.extend(make_seg(s) for s in item.split(" ") if s.strip())
            else:
                segs.append(make_seg(item))
        duan_lessons.append({
            "title": "文章 第 %d 篇" % idx,
            "segments": segs,
        })
    groups.append({"id": "wenzhang", "name": "文章段落", "desc": "段落长句 %d 段" % len(PARAGRAPHS),
                   "lessons": duan_lessons})

    return {"version": 1, "groups": groups}


TONE_MAP = {
    "ā": "a", "á": "a", "ǎ": "a", "à": "a",
    "ō": "o", "ó": "o", "ǒ": "o", "ò": "o",
    "ē": "e", "é": "e", "ě": "e", "è": "e",
    "ī": "i", "í": "i", "ǐ": "i", "ì": "i",
    "ū": "u", "ú": "u", "ǔ": "u", "ù": "u",
    "ǖ": "v", "ǘ": "v", "ǚ": "v", "ǜ": "v", "ü": "v",
}


def _strip_tone(s):
    return "".join(TONE_MAP.get(ch, ch) for ch in s)


def build_dict():
    lines = []
    for code in range(0x4E00, 0xA000):
        ch = chr(code)
        try:
            p = pinyin(ch, style=Style.TONE, heteronym=False)
        except Exception:
            continue
        if not p or not p[0] or not p[0][0]:
            continue
        val = p[0][0]
        if val == ch:  # 没有读音
            continue
        lines.append("%s\t%s" % (ch, val))
    return lines


def build_common3500():
    """3500 常用字 -> 字 / 拼音 / 声母 / 韵母（韵母 ü 记作 v），供随机练习与专项换字"""
    src = os.path.join(os.path.dirname(__file__), "common3500_source.txt")
    raw = open(src, encoding="utf-8-sig").read()
    chars = list(dict.fromkeys([c for c in raw if "\u4e00" <= c <= "\u9fff"]))
    lines = []
    for ch in chars:
        py = pinyin(ch, style=Style.TONE, heteronym=False, errors=lambda x: "")[0][0]
        if not py:
            continue
        ini = pinyin(ch, style=Style.INITIALS, strict=True)[0][0]
        fin = pinyin(ch, style=Style.FINALS, strict=True)[0][0]
        lines.append("%s\t%s\t%s\t%s" % (ch, py, ini, fin))
    return lines


if __name__ == "__main__":
    dict_lines = build_dict()
    common_lines = build_common3500()
    cjk = [ln.split("\t")[0] for ln in dict_lines]
    corpus = build(cjk_chars=cjk)
    with open(os.path.join(OUT_ASSETS, "corpus.json"), "w", encoding="utf-8") as f:
        json.dump(corpus, f, ensure_ascii=False, separators=(",", ":"))

    with open(os.path.join(OUT_ASSETS, "pinyin.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(dict_lines))

    with open(os.path.join(OUT_ASSETS, "common3500.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(common_lines) + "\n")

    n_lessons = sum(len(g["lessons"]) for g in corpus["groups"])
    print("corpus: %d groups / %d lessons" % (len(corpus["groups"]), n_lessons))
    print("dict  : %d chars" % len(dict_lines))
    print("common: %d chars" % len(common_lines))
    for p in ("corpus.json", "pinyin.txt", "common3500.txt"):
        print("  %s -> %.1f KB" % (p, os.path.getsize(os.path.join(OUT_ASSETS, p)) / 1024))
