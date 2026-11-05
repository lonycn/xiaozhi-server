-- CosyVoice-v2 official catalog, verified 2026-11-02.
-- Source: https://help.aliyun.com/zh/model-studio/cosyvoice-voice-list
-- Only extend alibl_stream configurations that explicitly use cosyvoice-v2.
-- Preserve existing voice IDs, labels, language settings, demos, and agent selections.
CREATE TEMPORARY TABLE cosyvoice_v2_seed_20261102 (
  code VARCHAR(50) NOT NULL PRIMARY KEY,
  name VARCHAR(20) NOT NULL,
  languages VARCHAR(50) NOT NULL,
  category VARCHAR(100) NOT NULL,
  demo VARCHAR(500) NOT NULL,
  sort INT UNSIGNED NOT NULL
) CHARACTER SET utf8mb4;

INSERT INTO cosyvoice_v2_seed_20261102 (code, name, languages, category, demo, sort) VALUES
  ('longxiaochun_v2', '龙小淳 · 知性积极女', '普通话、英文', '语音助手', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/lzchvc/%E9%BE%99%E5%B0%8F%E6%B7%B3.mp3', 0),
  ('longxiaoxia_v2', '龙小夏 · 沉稳权威女', '普通话、英文', '语音助手', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/hefbjz/%E9%BE%99%E5%B0%8F%E5%A4%8F.mp3', 1),
  ('longanlang', '龙安朗 · 清爽利落男', '普通话、英文', '语音助手', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/tsrnwi/%E9%BE%99%E5%AE%89%E6%9C%97.mp3', 2),
  ('longanyun', '龙安昀 · 居家暖男', '普通话、英文', '语音助手', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/qxvhdx/%E9%BE%99%E5%AE%89%E6%98%80.mp3', 3),
  ('longcheng_v2', '龙橙 · 智慧青年男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/yxlcev/%E9%BE%99%E6%A9%99.mp3', 4),
  ('longyingxiao', '龙应笑 · 清甜推销女', '普通话、英文', '电话销售', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/hvbyrx/%E9%BE%99%E5%BA%94%E7%AC%91.mp3', 5),
  ('longjiqi', '龙机器 · 呆萌机器人', '普通话、英文', '短视频配音', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/vqyqta/%E9%BE%99%E6%9C%BA%E5%99%A8.mp3', 6),
  ('longhouge', '龙猴哥 · 经典猴哥', '普通话、英文', '短视频配音', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/slzkaa/%E9%BE%99%E7%8C%B4%E5%93%A5.mp3', 7),
  ('longjixin', '龙机心 · 毒舌心机女', '普通话、英文', '短视频配音', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/ovssxn/%E9%BE%99%E6%9C%BA%E5%BF%83.mp3', 8),
  ('longanyue', '龙安粤 · 欢脱粤语男', '粤语、英文', '短视频配音', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/sluhct/%E9%BE%99%E5%AE%89%E7%B2%A4.mp3', 9),
  ('longshange', '龙陕哥 · 原味陕北男', '陕西话、英文', '短视频配音', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/vqomyc/%E9%BE%99%E9%99%95%E5%93%A5.mp3', 10),
  ('longanmin', '龙安敏 · 甜美闽南女', '闽南话、英文', '短视频配音', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/mcmxgh/%E9%BE%99%E5%AE%89%E6%95%8F.mp3', 11),
  ('longdaiyu', '龙黛玉 · 娇率才女音', '普通话、英文', '短视频配音', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/wywrbk/%E9%BE%99%E9%BB%9B%E7%8E%89.mp3', 12),
  ('longgaoseng', '龙高僧 · 得道高僧音', '普通话、英文', '短视频配音', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/zvvknx/%E9%BE%99%E9%AB%98%E5%83%A7.mp3', 13),
  ('longanli', '龙安莉 · 利落从容女', '普通话、英文', '语音助手', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/qeupsn/%E9%BE%99%E5%AE%89%E8%8E%89.mp3', 14),
  ('longanwen', '龙安温 · 优雅知性女', '普通话、英文', '语音助手', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/yxzzsu/%E9%BE%99%E5%AE%89%E6%B8%A9.mp3', 15),
  ('longyumi_v2', 'YUMI · 正经青年女', '普通话、英文', '语音助手', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/rbfsft/YUMI.mp3', 16),
  ('longyichen', '龙逸尘 · 洒脱活力男', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/pperyq/%E9%BE%99%E9%80%B8%E5%B0%98.mp3', 17),
  ('longwanjun', '龙婉君 · 细腻柔声女', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/tacvta/%E9%BE%99%E5%A9%89%E5%90%9B.mp3', 18),
  ('longlaobo', '龙老伯 · 沧桑岁月爷', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/kudwut/%E9%BE%99%E8%80%81%E4%BC%AF.mp3', 19),
  ('longlaoyi', '龙老姨 · 烟火从容阿姨', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/zspyhi/%E9%BE%99%E8%80%81%E5%A7%A8.mp3', 20),
  ('longbaizhi', '龙白芷 · 睿气旁白女', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/ypyjeg/%E9%BE%99%E7%99%BD%E8%8A%B7.mp3', 21),
  ('longsanshu', '龙三叔 · 沉稳质感男', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/otqtwd/%E9%BE%99%E4%B8%89%E5%8F%94.mp3', 22),
  ('longxiu_v2', '龙修 · 博才说书男', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/isfcla/%E9%BE%99%E4%BF%AE.mp3', 23),
  ('longmiao_v2', '龙妙 · 抑扬顿挫女', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/ynecbl/%E9%BE%99%E5%A6%99.mp3', 24),
  ('longyue_v2', '龙悦 · 温暖磁性女', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/xladsm/%E9%BE%99%E6%82%A6.mp3', 25),
  ('longnan_v2', '龙楠 · 睿智青年男', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/tojjob/%E9%BE%99%E6%A5%A0.mp3', 26),
  ('longyuan_v2', '龙媛 · 温暖治愈女', '普通话、英文', '有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/nqjskd/%E9%BE%99%E5%AA%9B.mp3', 27),
  ('longanqin', '龙安亲 · 亲和活泼女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/hiywdd/%E9%BE%99%E5%AE%89%E4%BA%B2.mp3', 28),
  ('longanya', '龙安雅 · 高雅气质女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/ynwlzs/%E9%BE%99%E5%AE%89%E9%9B%85.mp3', 29),
  ('longanshuo', '龙安朔 · 干净清爽男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/nybufr/%E9%BE%99%E5%AE%89%E6%9C%94.mp3', 30),
  ('longanling', '龙安灵 · 思维灵动女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/lwdcfc/%E9%BE%99%E5%AE%89%E7%81%B5.mp3', 31),
  ('longanzhi', '龙安智 · 睿智轻熟男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/sovode/%E9%BE%99%E5%AE%89%E6%99%BA.mp3', 32),
  ('longanrou', '龙安柔 · 温柔闺蜜女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/koxfwm/%E9%BE%99%E5%AE%89%E6%9F%94.mp3', 33),
  ('longqiang_v2', '龙嫱 · 浪漫风情女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/ycatrj/%E9%BE%99%E5%AB%B1.mp3', 34),
  ('longhan_v2', '龙寒 · 温暖痴情男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/npcvfg/%E9%BE%99%E5%AF%92.mp3', 35),
  ('longxing_v2', '龙星 · 温婉邻家女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/gqlaod/%E9%BE%99%E6%98%9F.mp3', 36),
  ('longhua_v2', '龙华 · 元气甜美女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/cvtzjh/%E9%BE%99%E5%8D%8E.mp3', 37),
  ('longwan_v2', '龙婉 · 积极知性女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/ybizun/%E9%BE%99%E5%A9%89.mp3', 38),
  ('longfeifei_v2', '龙菲菲 · 甜美娇气女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/afkidl/%E9%BE%99%E8%8F%B2%E8%8F%B2.mp3', 39),
  ('longxiaocheng_v2', '龙小诚 · 磁性低音男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/sjhntj/%E9%BE%99%E5%B0%8F%E8%AF%9A.mp3', 40),
  ('longzhe_v2', '龙哲 · 呆板大暖男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/wogvqz/%E9%BE%99%E5%93%B2.mp3', 41),
  ('longyan_v2', '龙颜 · 温暖春风女', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/xtfsbk/%E9%BE%99%E9%A2%9C.mp3', 42),
  ('longtian_v2', '龙天 · 磁性理智男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/vkbkhz/%E9%BE%99%E5%A4%A9.mp3', 43),
  ('longze_v2', '龙泽 · 温暖元气男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/mdolqm/%E9%BE%99%E6%B3%BD.mp3', 44),
  ('longshao_v2', '龙邵 · 积极向上男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/krbwty/%E9%BE%99%E9%82%B5.mp3', 45),
  ('longhao_v2', '龙浩 · 多情忧郁男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/lbljnc/%E9%BE%99%E6%B5%A9.mp3', 46),
  ('kabuleshen_v2', '龙深 · 实力歌手男', '普通话、英文', '社交陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250624/uqtctc/%E9%BE%99%E6%B7%B1.mp3', 47),
  ('longhuhu', '龙呼呼 · 天真烂漫女童', '普通话、英文', '童声（标杆音色）', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/zfvpjw/%E9%BE%99%E5%91%BC%E5%91%BC.mp3', 48),
  ('longanpei', '龙安培 · 青少年教师女', '普通话、英文', '消费电子-教育培训', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/qpyqha/%E9%BE%99%E5%AE%89%E5%9F%B9.mp3', 49),
  ('longwangwang', '龙汪汪 · 台湾少年音', '普通话、英文', '消费电子-儿童陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/goftfb/%E9%BE%99%E6%B1%AA%E6%B1%AA.mp3', 50),
  ('longpaopao', '龙泡泡 · 飞天泡泡音', '普通话、英文', '消费电子-儿童陪伴', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/usrfom/%E9%BE%99%E6%B3%A1%E6%B3%A1.mp3', 51),
  ('longshanshan', '龙闪闪 · 戏剧化童声', '普通话、英文', '消费电子-儿童有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/nnygwq/%E9%BE%99%E9%97%AA%E9%97%AA.mp3', 52),
  ('longniuniu', '龙牛牛 · 阳光男童声', '普通话、英文', '消费电子-儿童有声书', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/yeljel/%E9%BE%99%E7%89%9B%E7%89%9B.mp3', 53),
  ('longyingmu', '龙应沐 · 优雅知性女', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/daptml/%E9%BE%99%E5%BA%94%E6%B2%90.mp3', 54),
  ('longyingxun', '龙应询 · 年轻青涩男', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/lezfco/%E9%BE%99%E5%BA%94%E8%AF%A2.mp3', 55),
  ('longyingcui', '龙应催 · 严肃催收男', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/yyoson/%E9%BE%99%E5%BA%94%E5%82%AC.mp3', 56),
  ('longyingda', '龙应答 · 开朗高音女', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/eoxghk/%E9%BE%99%E5%BA%94%E7%AD%94.mp3', 57),
  ('longyingjing', '龙应静 · 低调冷静女', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/nbabuh/%E9%BE%99%E5%BA%94%E9%9D%99.mp3', 58),
  ('longyingyan', '龙应严 · 义正严辞女', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/vufxoj/%E9%BE%99%E5%BA%94%E4%B8%A5.mp3', 59),
  ('longyingtian', '龙应甜 · 温柔甜美女', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/fiiebi/%E9%BE%99%E5%BA%94%E7%94%9C.mp3', 60),
  ('longyingbing', '龙应冰 · 尖锐强势女', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/ucqara/%E9%BE%99%E5%BA%94%E5%86%B0.mp3', 61),
  ('longyingtao', '龙应桃 · 温柔淡定女', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/ovwghc/%E9%BE%99%E5%BA%94%E6%A1%83.mp3', 62),
  ('longyingling', '龙应聆 · 温和共情女', '普通话、英文', '客服', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/ldntih/%E9%BE%99%E5%BA%94%E8%81%86.mp3', 63),
  ('longanran', '龙安燃 · 活泼质感女', '普通话、英文', '直播带货', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/smffyb/%E9%BE%99%E5%AE%89%E7%87%83.mp3', 64),
  ('longanxuan', '龙安宣 · 经典直播女', '普通话、英文', '直播带货', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/kcnyee/%E9%BE%99%E5%AE%89%E5%AE%A3.mp3', 65),
  ('longanchong', '龙安冲 · 激情推销男', '普通话、英文', '直播带货', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/vovswj/%E9%BE%99%E5%AE%89%E5%86%B2.mp3', 66),
  ('longanping', '龙安萍 · 高亢直播女', '普通话、英文', '直播带货', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250813/otftuf/%E9%BE%99%E5%AE%89%E8%90%8D.mp3', 67),
  ('longjielidou_v2', '龙杰力豆 · 阳光顽皮男', '普通话、英文', '童声', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/xqrzon/%E9%BE%99%E6%9D%B0%E5%8A%9B%E8%B1%86.mp3', 68),
  ('longling_v2', '龙铃 · 稚气呆板女', '普通话、英文', '童声', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/qunivx/%E9%BE%99%E9%93%83.mp3', 69),
  ('longke_v2', '龙可 · 懵懂乖乖女', '普通话、英文', '童声', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/fpavdo/%E9%BE%99%E5%8F%AF.mp3', 70),
  ('longxian_v2', '龙仙 · 豪放可爱女', '普通话、英文', '童声', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/hethqh/%E9%BE%99%E4%BB%99.mp3', 71),
  ('longlaotie_v2', '龙老铁 · 东北直率男', '东北话、英文', '方言', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/vomcqn/%E9%BE%99%E8%80%81%E9%93%81.mp3', 72),
  ('longjiayi_v2', '龙嘉怡 · 知性粤语女', '粤语、英文', '方言', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/gzugcz/%E9%BE%99%E5%98%89%E6%80%A1.mp3', 73),
  ('longtao_v2', '龙桃 · 积极粤语女', '粤语、英文', '方言', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/fqkjrp/%E9%BE%99%E6%A1%83.mp3', 74),
  ('longfei_v2', '龙飞 · 热血磁性男', '普通话、英文', '诗词朗诵', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/liqtfn/%E9%BE%99%E9%A3%9E.mp3', 75),
  ('libai_v2', '李白 · 古代诗仙男', '普通话、英文', '诗词朗诵', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/jkrnti/%E6%9D%8E%E7%99%BD.mp3', 76),
  ('longjin_v2', '龙津 · 优雅温润男', '普通话、英文', '诗词朗诵', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/yfsrer/%E9%BE%99%E6%B4%A5.mp3', 77),
  ('longshu_v2', '龙书 · 沉稳青年男', '普通话、英文', '新闻播报', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/zihuud/%E9%BE%99%E4%B9%A6.mp3', 78),
  ('loongbella_v2', 'Bella2.0 · 精准干练女', '普通话、英文', '新闻播报', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/jbxouw/Bella2.0.mp3', 79),
  ('longshuo_v2', '龙硕 · 博才干练男', '普通话、英文', '新闻播报', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/mhfnjs/%E9%BE%99%E7%A1%95.mp3', 80),
  ('longxiaobai_v2', '龙小白 · 沉稳播报女', '普通话、英文', '新闻播报', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/ruqhkp/%E9%BE%99%E5%B0%8F%E7%99%BD.mp3', 81),
  ('longjing_v2', '龙婧 · 典型播音女', '普通话、英文', '新闻播报', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/yryekx/%E9%BE%99%E5%A9%A7.mp3', 82),
  ('loongstella_v2', 'loongstella · 飒爽利落女', '普通话、英文', '新闻播报', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250617/jfnolr/loongstella.mp3', 83),
  ('loongyuuna_v2', 'loongyuuna · 元气霓虹女', '日语', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/udwmjr/loongyuuna.mp3', 84),
  ('loongyuuma_v2', 'loongyuuma · 干练霓虹男', '日语', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/rtivvj/loongyuuma.mp3', 85),
  ('loongjihun_v2', 'loongjihun · 阳光韩国男', '韩语', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251020/eukhgt/loongjihun.mp3', 86),
  ('loongeva_v2', 'loongeva · 知性英文女', '英式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/xyivxq/loongeva.wav', 87),
  ('loongbrian_v2', 'loongbrian · 沉稳英文男', '英式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/evasww/loongbrian.wav', 88),
  ('loongluna_v2', 'loongluna · 英式英文女', '英式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/otfvbi/loongluna.wav', 89),
  ('loongluca_v2', 'loongluca · 英式英文男', '英式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/qlvogk/loongluca.wav', 90),
  ('loongemily_v2', 'loongemily · 英式英文女', '英式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/mprrsl/loongemily.wav', 91),
  ('loongeric_v2', 'loongeric · 英式英文男', '英式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/nonkfg/loongeric.wav', 92),
  ('loongabby_v2', 'loongabby · 美式英文女', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/rldxbm/loongabby.wav', 93),
  ('loongannie_v2', 'loongannie · 美式英文女', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/clmmzb/loongannie.wav', 94),
  ('loongandy_v2', 'loongandy · 美式英文男', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/mqqzei/loongandy.wav', 95),
  ('loongava_v2', 'loongava · 美式英文女', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/vtqwat/loongava.wav', 96),
  ('loongbeth_v2', 'loongbeth · 美式英文女', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/rcpeak/loongbeth.wav', 97),
  ('loongbetty_v2', 'loongbetty · 美式英文女', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/zmyqjd/loongbetty.wav', 98),
  ('loongcindy_v2', 'loongcindy · 美式英文女', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/umqsuu/loongcindy.wav', 99),
  ('loongcally_v2', 'loongcally · 美式英文女', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/ebfujn/loongcally.wav', 100),
  ('loongdavid_v2', 'loongdavid · 美式英文男', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/kbhqis/loongdavid.wav', 101),
  ('loongdonna_v2', 'loongdonna · 美式英文女', '美式英文', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/yjpiqa/loongdonna.wav', 102),
  ('loongkyong_v2', 'loongkyong · 韩语女', '韩语', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/cwjeat/loongkyong.wav', 103),
  ('loongtomoka_v2', 'loongtomoka · 日语女', '日语', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/psiupe/loongtomoka.wav', 104),
  ('loongtomoya_v2', 'loongtomoya · 日语男', '日语', '出海营销', 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250618/mjjhps/loongtomoya.wav', 105);

INSERT INTO ai_tts_voice
  (id, tts_model_id, name, tts_voice, languages, voice_demo, remark, sort, create_date)
SELECT MD5(CONCAT('cosyvoice-v2:', model.id, ':', seed.code)),
       model.id, seed.name, seed.code, seed.languages, seed.demo, seed.category, seed.sort, NOW()
FROM ai_model_config model
CROSS JOIN cosyvoice_v2_seed_20261102 seed
WHERE model.model_type = 'TTS'
  AND JSON_UNQUOTE(JSON_EXTRACT(model.config_json, '$.type')) = 'alibl_stream'
  AND JSON_UNQUOTE(JSON_EXTRACT(model.config_json, '$.model')) = 'cosyvoice-v2'
  AND NOT EXISTS (
    SELECT 1 FROM ai_tts_voice existing
    WHERE existing.tts_model_id = model.id AND existing.tts_voice = seed.code
  );

-- Add official previews only where the current voice has no preview URL.
UPDATE ai_tts_voice voice
JOIN ai_model_config model ON model.id = voice.tts_model_id
JOIN cosyvoice_v2_seed_20261102 seed ON seed.code = voice.tts_voice
SET voice.voice_demo = seed.demo
WHERE model.model_type = 'TTS'
  AND JSON_UNQUOTE(JSON_EXTRACT(model.config_json, '$.type')) = 'alibl_stream'
  AND JSON_UNQUOTE(JSON_EXTRACT(model.config_json, '$.model')) = 'cosyvoice-v2'
  AND (voice.voice_demo IS NULL OR TRIM(voice.voice_demo) = '');

DROP TEMPORARY TABLE cosyvoice_v2_seed_20261102;
