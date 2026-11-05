"""Conservative whole-utterance controls. Never match quoted/explanatory substrings."""
import re
import unicodedata

WAKE_ADDRESSES = ('大冷总管', '你好总管', '总管总管', '你好中管', '中管')
CONTROL_PREFIX = r'(?:好的|好了|好嘞|好|嗯|谢谢|没事了|不用了|我?知道了|我?明白了|我?了解了|请|现在){0,4}'
COMMAND_ENDING = r'[吧了呀啊啦喽]{0,2}'
EXIT_WORDS = ('退出', '再见', '拜拜', '退下吧', '退下', '退一下', '结束对话', '结束聊天', '不聊了', '推下吧')
STOP_WORDS = ('停止', '停止播放', '停止播报', '停止说话', '停止回答', '别说了', '别别说了',
              '不要说了', '不用说了', '停一下', '暂停播放', '暂停一下', '打断一下')
STOP_ALIASES = ('暂停', '暂停播报', '先停一下', '先别说了')


def _command(words):
    return '(?:' + '|'.join(re.escape(word) for word in sorted(words, key=len, reverse=True)) + ')' + COMMAND_ENDING


_EXIT = _command(EXIT_WORDS)
_STOP = _command(STOP_WORDS)
_STOP_ALIAS = _command(STOP_ALIASES)
_CONTROL = _command(EXIT_WORDS + STOP_WORDS + STOP_ALIASES)
_EXIT_SEQUENCE = re.compile(CONTROL_PREFIX + rf'(?:{_CONTROL}){{0,5}}{_EXIT}(?:{_CONTROL}){{0,5}}(?:谢谢)?')
_STOP_SEQUENCE = re.compile(CONTROL_PREFIX + rf'(?:{_STOP_ALIAS}){{0,5}}{_STOP}(?:{_STOP}|{_STOP_ALIAS}){{0,5}}(?:谢谢)?')
_CANCEL_EXIT = re.compile(CONTROL_PREFIX + rf'(?:停止|暂停)退出{COMMAND_ENDING}(?:谢谢)?')
_SEPARATE_EXIT = re.compile(r'(?:停止|暂停)[\s,，、。；;]+退出')


def normalized(text):
    if not isinstance(text, str) or len(text)>200:
        return ''
    return ''.join(c for c in text if not c.isspace() and not unicodedata.category(c).startswith('P'))


def classify(text):
    value=normalized(text)
    address = next((word for word in WAKE_ADDRESSES if value.startswith(word)), '')
    value = value[len(address):]
    if not value:
        return None
    if _EXIT_SEQUENCE.fullmatch(value):
        # “停止退出” cancels an exit. An explicit separator makes it two commands.
        if not _CANCEL_EXIT.fullmatch(value) or _SEPARATE_EXIT.search(text):
            return 'exit'
    if _STOP_SEQUENCE.fullmatch(value):
        return 'stop'
    return None
