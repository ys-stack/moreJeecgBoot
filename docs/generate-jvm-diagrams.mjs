import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const imageDir = path.join(__dirname, "images");
fs.mkdirSync(imageDir, { recursive: true });

const palette = {
  bg: "#f8fafc",
  ink: "#172033",
  muted: "#607086",
  blue: "#2563eb",
  blueSoft: "#dbeafe",
  green: "#16a34a",
  greenSoft: "#dcfce7",
  amber: "#d97706",
  amberSoft: "#fef3c7",
  rose: "#e11d48",
  roseSoft: "#ffe4e6",
  violet: "#7c3aed",
  violetSoft: "#ede9fe",
  gray: "#e2e8f0",
  white: "#ffffff",
};

function esc(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function splitLines(label) {
  return String(label).split(/\n|<br\/?>/g);
}

function svgShell(width, height, title, body) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${esc(title)}">
  <defs>
    <marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto" markerUnits="strokeWidth">
      <path d="M0,0 L0,6 L8,3 z" fill="${palette.muted}" />
    </marker>
    <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%">
      <feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="#0f172a" flood-opacity="0.12"/>
    </filter>
  </defs>
  <rect width="100%" height="100%" rx="18" fill="${palette.bg}"/>
  <text x="32" y="42" font-family="Microsoft YaHei, PingFang SC, Arial, sans-serif" font-size="22" font-weight="700" fill="${palette.ink}">${esc(title)}</text>
  ${body}
</svg>
`;
}

function rectNode({ x, y, w, h, label, fill = palette.white, stroke = palette.blue, text = palette.ink, r = 12 }) {
  const lines = splitLines(label);
  const lineHeight = 20;
  const startY = y + h / 2 - ((lines.length - 1) * lineHeight) / 2 + 6;
  const tspans = lines
    .map((line, index) => `<tspan x="${x + w / 2}" y="${startY + index * lineHeight}">${esc(line)}</tspan>`)
    .join("");
  return `
  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill}" stroke="${stroke}" stroke-width="2" filter="url(#shadow)"/>
  <text font-family="Microsoft YaHei, PingFang SC, Arial, sans-serif" font-size="15" font-weight="600" fill="${text}" text-anchor="middle">${tspans}</text>`;
}

function diamondNode({ x, y, w, h, label, fill = palette.amberSoft, stroke = palette.amber }) {
  const cx = x + w / 2;
  const cy = y + h / 2;
  const lines = splitLines(label);
  const lineHeight = 20;
  const startY = cy - ((lines.length - 1) * lineHeight) / 2 + 6;
  const tspans = lines
    .map((line, index) => `<tspan x="${cx}" y="${startY + index * lineHeight}">${esc(line)}</tspan>`)
    .join("");
  return `
  <polygon points="${cx},${y} ${x + w},${cy} ${cx},${y + h} ${x},${cy}" fill="${fill}" stroke="${stroke}" stroke-width="2" filter="url(#shadow)"/>
  <text font-family="Microsoft YaHei, PingFang SC, Arial, sans-serif" font-size="14" font-weight="700" fill="${palette.ink}" text-anchor="middle">${tspans}</text>`;
}

function arrow(x1, y1, x2, y2, label = "") {
  const midX = (x1 + x2) / 2;
  const midY = (y1 + y2) / 2;
  const labelSvg = label
    ? `<text x="${midX}" y="${midY - 8}" font-family="Microsoft YaHei, PingFang SC, Arial, sans-serif" font-size="13" font-weight="600" fill="${palette.muted}" text-anchor="middle">${esc(label)}</text>`
    : "";
  return `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${palette.muted}" stroke-width="2.2" marker-end="url(#arrow)"/>${labelSvg}`;
}

function doubleArrow(x1, y1, x2, y2) {
  return `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${palette.muted}" stroke-width="2.2" marker-end="url(#arrow)"/>
  <line x1="${x2}" y1="${y2 + 8}" x2="${x1}" y2="${y1 + 8}" stroke="${palette.muted}" stroke-width="2.2" marker-end="url(#arrow)"/>`;
}

function writeSvg(file, width, height, title, body) {
  fs.writeFileSync(path.join(imageDir, file), svgShell(width, height, title, body), "utf8");
}

writeSvg(
  "jvm-runtime-memory.svg",
  1180,
  720,
  "JVM 运行时内存结构",
  [
    rectNode({ x: 490, y: 70, w: 220, h: 58, label: "JVM 运行时数据区", fill: palette.blueSoft }),
    rectNode({ x: 70, y: 180, w: 165, h: 72, label: "程序计数器\n线程私有", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 270, y: 180, w: 180, h: 72, label: "Java 虚拟机栈\n线程私有", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 490, y: 180, w: 180, h: 72, label: "本地方法栈\n线程私有", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 710, y: 180, w: 170, h: 72, label: "Java 堆\n线程共享", fill: palette.violetSoft, stroke: palette.violet }),
    rectNode({ x: 920, y: 180, w: 190, h: 72, label: "方法区 / 元空间\n线程共享", fill: palette.violetSoft, stroke: palette.violet }),
    rectNode({ x: 450, y: 600, w: 250, h: 60, label: "直接内存\n堆外内存，NIO/Netty 常见", fill: palette.amberSoft, stroke: palette.amber }),
    arrow(600, 128, 152, 180),
    arrow(600, 128, 360, 180),
    arrow(600, 128, 580, 180),
    arrow(600, 128, 795, 180),
    arrow(600, 128, 1015, 180),
    arrow(600, 128, 575, 600),
    rectNode({ x: 255, y: 320, w: 210, h: 52, label: "栈帧", fill: palette.white }),
    arrow(360, 252, 360, 320),
    ...[
      ["局部变量表", 120],
      ["操作数栈", 300],
      ["动态链接", 480],
      ["返回地址", 660],
    ].map(([label, x]) => rectNode({ x, y: 420, w: 145, h: 50, label, fill: palette.white })),
    arrow(360, 372, 192, 420),
    arrow(360, 372, 372, 420),
    arrow(360, 372, 552, 420),
    arrow(360, 372, 732, 420),
    rectNode({ x: 700, y: 320, w: 170, h: 52, label: "新生代", fill: palette.white }),
    rectNode({ x: 905, y: 320, w: 170, h: 52, label: "老年代", fill: palette.white }),
    arrow(795, 252, 785, 320),
    arrow(795, 252, 990, 320),
    ...[
      ["Eden", 615],
      ["Survivor 0", 775],
      ["Survivor 1", 935],
    ].map(([label, x]) => rectNode({ x, y: 420, w: 130, h: 50, label, fill: palette.white })),
    arrow(785, 372, 680, 420),
    arrow(785, 372, 840, 420),
    arrow(785, 372, 1000, 420),
    ...[
      ["类元信息", 900],
      ["运行时常量池", 900],
      ["静态变量相关信息", 900],
    ].map(([label, x], i) => rectNode({ x, y: 310 + i * 82, w: 205, h: 50, label, fill: palette.white })),
    arrow(1015, 252, 1002, 310),
    arrow(1015, 252, 1002, 392),
    arrow(1015, 252, 1002, 474),
  ].join("\n"),
);

writeSvg(
  "method-area-evolution.svg",
  760,
  260,
  "方法区实现变化",
  [
    rectNode({ x: 80, y: 110, w: 240, h: 70, label: "Java 7 及以前\n方法区常由永久代实现", fill: palette.amberSoft, stroke: palette.amber }),
    rectNode({ x: 440, y: 110, w: 240, h: 70, label: "Java 8 以后\n移除永久代，改为元空间", fill: palette.greenSoft, stroke: palette.green }),
    arrow(320, 145, 440, 145, "演进"),
  ].join("\n"),
);

function chainDiagram(file, title, labels, width = 1120) {
  const y = 118;
  const w = 140;
  const gap = (width - 80 - labels.length * w) / (labels.length - 1);
  const body = labels
    .map((label, i) => {
      const x = 40 + i * (w + gap);
      const node = rectNode({ x, y, w, h: 58, label, fill: i === 0 ? palette.blueSoft : palette.white });
      const arr = i < labels.length - 1 ? arrow(x + w, y + 29, x + w + gap, y + 29) : "";
      return node + arr;
    })
    .join("\n");
  writeSvg(file, width, 250, title, body);
}

chainDiagram("object-creation.svg", "对象创建流程", ["new Object()", "类加载检查", "分配内存", "初始化零值", "设置对象头", "执行构造方法", "返回引用"], 1200);
chainDiagram("class-loading.svg", "类加载过程", ["加载", "验证", "准备", "解析", "初始化", "使用", "卸载"], 1040);
chainDiagram("cms-flow.svg", "CMS 回收流程", ["初始标记\nSTW", "并发标记", "重新标记\nSTW", "并发清除"], 760);
chainDiagram("g1-cycle.svg", "G1 常见回收阶段", ["Young GC", "并发标记", "最终标记 / Remark", "清理", "Mixed GC"], 940);

writeSvg(
  "object-layout.svg",
  900,
  360,
  "Java 对象内存布局",
  [
    rectNode({ x: 60, y: 145, w: 170, h: 70, label: "Java 对象", fill: palette.blueSoft }),
    rectNode({ x: 330, y: 70, w: 170, h: 60, label: "对象头", fill: palette.violetSoft, stroke: palette.violet }),
    rectNode({ x: 330, y: 160, w: 170, h: 60, label: "实例数据", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 330, y: 250, w: 170, h: 60, label: "对齐填充", fill: palette.amberSoft, stroke: palette.amber }),
    arrow(230, 180, 330, 100),
    arrow(230, 180, 330, 190),
    arrow(230, 180, 330, 280),
    rectNode({ x: 630, y: 35, w: 210, h: 54, label: "Mark Word\nhashCode / 锁状态", fill: palette.white }),
    rectNode({ x: 630, y: 120, w: 210, h: 54, label: "类型指针\n指向类元信息", fill: palette.white }),
    rectNode({ x: 630, y: 205, w: 210, h: 54, label: "数组长度\n仅数组对象存在", fill: palette.white }),
    arrow(500, 100, 630, 62),
    arrow(500, 100, 630, 147),
    arrow(500, 100, 630, 232),
  ].join("\n"),
);

writeSvg(
  "tlab.svg",
  860,
  360,
  "TLAB：线程本地分配缓冲区",
  [
    rectNode({ x: 80, y: 100, w: 700, h: 190, label: "Eden 区", fill: "#eef6ff", stroke: palette.blue }),
    ...[
      ["线程 A 的 TLAB", 125],
      ["线程 B 的 TLAB", 315],
      ["线程 C 的 TLAB", 505],
      ["公共分配区域", 315],
    ].map(([label, x], i) => rectNode({ x, y: i === 3 ? 210 : 135, w: 160, h: 52, label, fill: i === 3 ? palette.amberSoft : palette.white, stroke: i === 3 ? palette.amber : palette.green })),
  ].join("\n"),
);

writeSvg(
  "parent-delegation.svg",
  760,
  480,
  "双亲委派模型",
  [
    rectNode({ x: 250, y: 70, w: 260, h: 64, label: "Bootstrap ClassLoader\n启动类加载器", fill: palette.blueSoft }),
    rectNode({ x: 250, y: 170, w: 260, h: 64, label: "Platform ClassLoader\n平台类加载器", fill: palette.white }),
    rectNode({ x: 250, y: 270, w: 260, h: 64, label: "Application ClassLoader\n应用类加载器", fill: palette.white }),
    rectNode({ x: 250, y: 370, w: 260, h: 64, label: "Custom ClassLoader\n自定义类加载器", fill: palette.white }),
    arrow(380, 370, 380, 334, "先委托父加载器"),
    arrow(380, 270, 380, 234),
    arrow(380, 170, 380, 134),
  ].join("\n"),
);

writeSvg(
  "gc-roots.svg",
  880,
  440,
  "可达性分析",
  [
    rectNode({ x: 60, y: 185, w: 150, h: 60, label: "GC Roots", fill: palette.blueSoft }),
    rectNode({ x: 290, y: 90, w: 130, h: 54, label: "对象 A", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 490, y: 90, w: 130, h: 54, label: "对象 B", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 690, y: 90, w: 130, h: 54, label: "对象 C", fill: palette.greenSoft, stroke: palette.green }),
    arrow(210, 215, 290, 117),
    arrow(420, 117, 490, 117),
    arrow(620, 117, 690, 117),
    rectNode({ x: 290, y: 285, w: 130, h: 54, label: "对象 X", fill: palette.roseSoft, stroke: palette.rose }),
    rectNode({ x: 490, y: 285, w: 130, h: 54, label: "对象 Y", fill: palette.roseSoft, stroke: palette.rose }),
    rectNode({ x: 690, y: 285, w: 130, h: 54, label: "对象 Z", fill: palette.roseSoft, stroke: palette.rose }),
    arrow(420, 312, 490, 312),
    arrow(620, 312, 690, 312),
    `<line x1="210" y1="225" x2="290" y2="312" stroke="${palette.rose}" stroke-width="2" stroke-dasharray="6 6"/>`,
    `<text x="238" y="285" font-family="Microsoft YaHei, PingFang SC, Arial, sans-serif" font-size="14" font-weight="700" fill="${palette.rose}">不可达，可回收</text>`,
  ].join("\n"),
);

writeSvg(
  "generation-flow.svg",
  900,
  320,
  "分代收集与对象晋升",
  [
    rectNode({ x: 50, y: 135, w: 120, h: 54, label: "新对象", fill: palette.blueSoft }),
    rectNode({ x: 240, y: 135, w: 120, h: 54, label: "Eden", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 470, y: 135, w: 130, h: 54, label: "Survivor", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 730, y: 135, w: 120, h: 54, label: "老年代", fill: palette.violetSoft, stroke: palette.violet }),
    arrow(170, 162, 240, 162),
    arrow(360, 162, 470, 162, "Young GC 存活"),
    arrow(600, 162, 730, 162, "达到年龄阈值"),
    `<path d="M535 135 C535 72, 665 72, 665 135" fill="none" stroke="${palette.muted}" stroke-width="2.2" marker-end="url(#arrow)"/>`,
    `<text x="600" y="78" font-family="Microsoft YaHei, PingFang SC, Arial, sans-serif" font-size="13" font-weight="600" fill="${palette.muted}" text-anchor="middle">年龄增加</text>`,
    `<path d="M300 189 C380 250, 650 250, 750 189" fill="none" stroke="${palette.amber}" stroke-width="2.2" marker-end="url(#arrow)"/>`,
    `<text x="525" y="258" font-family="Microsoft YaHei, PingFang SC, Arial, sans-serif" font-size="13" font-weight="600" fill="${palette.amber}" text-anchor="middle">大对象可能直接进入老年代</text>`,
  ].join("\n"),
);

writeSvg(
  "g1-regions.svg",
  760,
  430,
  "G1 Region 化堆结构",
  [
    rectNode({ x: 70, y: 90, w: 620, h: 280, label: "G1 Heap", fill: "#eef6ff", stroke: palette.blue }),
    ...[
      ["Region: Eden", 120, 145, palette.greenSoft, palette.green],
      ["Region: Survivor", 300, 145, palette.greenSoft, palette.green],
      ["Region: Old", 480, 145, palette.violetSoft, palette.violet],
      ["Region: Humongous", 120, 245, palette.amberSoft, palette.amber],
      ["Region: Free", 300, 245, palette.white, palette.muted],
      ["Region: Old", 480, 245, palette.violetSoft, palette.violet],
    ].map(([label, x, y, fill, stroke]) => rectNode({ x, y, w: 140, h: 58, label, fill, stroke })),
  ].join("\n"),
);

writeSvg(
  "full-gc-log-judgement.svg",
  900,
  470,
  "Full GC 日志判断思路",
  [
    rectNode({ x: 330, y: 75, w: 220, h: 58, label: "发现 Full GC 频繁", fill: palette.blueSoft }),
    diamondNode({ x: 300, y: 175, w: 280, h: 110, label: "Full GC 后\nOld 区是否明显下降" }),
    rectNode({ x: 70, y: 345, w: 270, h: 64, label: "明显下降\n瞬时大对象 / 参数不合理 / 晋升过快", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 560, y: 325, w: 270, h: 64, label: "下降很少\n高度怀疑内存泄漏或长期引用", fill: palette.roseSoft, stroke: palette.rose }),
    rectNode({ x: 580, y: 400, w: 230, h: 48, label: "Dump 堆内存，MAT 分析", fill: palette.white, stroke: palette.rose }),
    arrow(440, 133, 440, 175),
    arrow(330, 250, 205, 345, "是"),
    arrow(550, 250, 695, 325, "否"),
    arrow(695, 389, 695, 400),
  ].join("\n"),
);

writeSvg(
  "cpu-high-runbook.svg",
  980,
  620,
  "CPU 飙高排查流程",
  [
    rectNode({ x: 380, y: 70, w: 220, h: 56, label: "CPU 飙高", fill: palette.roseSoft, stroke: palette.rose }),
    ...[
      ["top 查看高 CPU 进程", 380, 155],
      ["top -Hp pid 查看高 CPU 线程", 360, 235],
      ["线程 id 转十六进制", 380, 315],
      ["jstack / jcmd 查看线程栈", 360, 395],
      ["搜索 nid，定位具体代码", 370, 475],
    ].map(([label, x, y]) => rectNode({ x, y, w: 240, h: 54, label, fill: palette.white })),
    arrow(490, 126, 490, 155),
    arrow(490, 209, 490, 235),
    arrow(490, 289, 490, 315),
    arrow(490, 369, 490, 395),
    arrow(490, 449, 490, 475),
    diamondNode({ x: 680, y: 390, w: 190, h: 90, label: "线程状态" }),
    arrow(610, 422, 680, 435),
    rectNode({ x: 60, y: 520, w: 260, h: 58, label: "RUNNABLE\n死循环 / 复杂计算 / 频繁 GC", fill: palette.amberSoft, stroke: palette.amber }),
    rectNode({ x: 360, y: 520, w: 210, h: 58, label: "BLOCKED\n锁竞争严重", fill: palette.roseSoft, stroke: palette.rose }),
    rectNode({ x: 620, y: 520, w: 300, h: 58, label: "WAITING / TIMED_WAITING\n通常继续看其他高 CPU 线程", fill: palette.greenSoft, stroke: palette.green }),
    arrow(705, 480, 190, 520),
    arrow(775, 480, 465, 520),
    arrow(820, 480, 770, 520),
  ].join("\n"),
);

writeSvg(
  "memory-leak-runbook.svg",
  1040,
  650,
  "内存泄漏排查流程",
  [
    rectNode({ x: 410, y: 70, w: 220, h: 56, label: "怀疑内存泄漏", fill: palette.roseSoft, stroke: palette.rose }),
    rectNode({ x: 400, y: 150, w: 240, h: 56, label: "jstat 观察堆变化", fill: palette.white }),
    diamondNode({ x: 365, y: 235, w: 310, h: 95, label: "Old 区是否持续上涨" }),
    rectNode({ x: 70, y: 370, w: 240, h: 58, label: "否\n可能是瞬时流量或正常波动", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 400, y: 370, w: 240, h: 58, label: "是\n观察 Full GC 后 Old 是否下降", fill: palette.amberSoft, stroke: palette.amber }),
    diamondNode({ x: 365, y: 455, w: 310, h: 95, label: "下降是否明显" }),
    rectNode({ x: 70, y: 570, w: 260, h: 56, label: "明显\n对象创建过快或堆偏小", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 410, y: 570, w: 210, h: 56, label: "不明显\nDump 堆", fill: palette.roseSoft, stroke: palette.rose }),
    rectNode({ x: 720, y: 360, w: 250, h: 56, label: "MAT / JProfiler 分析", fill: palette.white }),
    rectNode({ x: 720, y: 445, w: 250, h: 56, label: "看 Dominator Tree", fill: palette.white }),
    rectNode({ x: 720, y: 530, w: 250, h: 56, label: "看 GC Roots 引用链", fill: palette.white }),
    arrow(520, 126, 520, 150),
    arrow(520, 206, 520, 235),
    arrow(365, 282, 190, 370, "否"),
    arrow(520, 330, 520, 370, "是"),
    arrow(520, 428, 520, 455),
    arrow(365, 502, 200, 570, "是"),
    arrow(520, 550, 520, 570, "否"),
    arrow(620, 598, 720, 388),
    arrow(845, 416, 845, 445),
    arrow(845, 501, 845, 530),
  ].join("\n"),
);

writeSvg(
  "full-gc-runbook.svg",
  1020,
  600,
  "频繁 Full GC 排查流程",
  [
    rectNode({ x: 400, y: 70, w: 220, h: 56, label: "频繁 Full GC", fill: palette.roseSoft, stroke: palette.rose }),
    rectNode({ x: 390, y: 150, w: 240, h: 56, label: "看 GC 日志触发原因", fill: palette.white }),
    diamondNode({ x: 350, y: 245, w: 320, h: 95, label: "Full GC 后\n内存是否下降" }),
    rectNode({ x: 60, y: 385, w: 230, h: 56, label: "下降少\n怀疑内存泄漏", fill: palette.roseSoft, stroke: palette.rose }),
    rectNode({ x: 380, y: 385, w: 270, h: 56, label: "下降多\n对象创建太快或堆参数不合理", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 720, y: 200, w: 230, h: 56, label: "Metaspace 高\n类加载过多或动态生成类", fill: palette.amberSoft, stroke: palette.amber }),
    rectNode({ x: 720, y: 315, w: 230, h: 56, label: "System.gc\n排查代码或依赖显式 GC", fill: palette.amberSoft, stroke: palette.amber }),
    rectNode({ x: 720, y: 430, w: 230, h: 56, label: "晋升失败\n调整比例或降低对象存活", fill: palette.amberSoft, stroke: palette.amber }),
    arrow(510, 126, 510, 150),
    arrow(510, 206, 510, 245),
    arrow(350, 292, 175, 385, "否"),
    arrow(535, 340, 515, 385, "是"),
    arrow(630, 178, 720, 228),
    arrow(630, 178, 720, 343),
    arrow(630, 178, 720, 458),
  ].join("\n"),
);

writeSvg(
  "jmm-memory-model.svg",
  860,
  390,
  "JMM 主内存与工作内存",
  [
    rectNode({ x: 320, y: 80, w: 220, h: 70, label: "主内存\n共享变量", fill: palette.blueSoft }),
    rectNode({ x: 80, y: 250, w: 190, h: 62, label: "线程 A 工作内存", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 335, y: 250, w: 190, h: 62, label: "线程 B 工作内存", fill: palette.greenSoft, stroke: palette.green }),
    rectNode({ x: 590, y: 250, w: 190, h: 62, label: "线程 C 工作内存", fill: palette.greenSoft, stroke: palette.green }),
    doubleArrow(360, 150, 175, 250),
    doubleArrow(430, 150, 430, 250),
    doubleArrow(500, 150, 685, 250),
  ].join("\n"),
);

const replacements = [
  ["JVM 运行时内存结构图", "images/jvm-runtime-memory.svg"],
  ["方法区实现变化图", "images/method-area-evolution.svg"],
  ["对象创建流程图", "images/object-creation.svg"],
  ["Java 对象内存布局图", "images/object-layout.svg"],
  ["TLAB 结构图", "images/tlab.svg"],
  ["类加载过程图", "images/class-loading.svg"],
  ["双亲委派模型图", "images/parent-delegation.svg"],
  ["可达性分析图", "images/gc-roots.svg"],
  ["分代收集与对象晋升图", "images/generation-flow.svg"],
  ["CMS 回收流程图", "images/cms-flow.svg"],
  ["G1 Region 化堆结构图", "images/g1-regions.svg"],
  ["G1 常见回收阶段图", "images/g1-cycle.svg"],
  ["Full GC 日志判断思路图", "images/full-gc-log-judgement.svg"],
  ["CPU 飙高排查流程图", "images/cpu-high-runbook.svg"],
  ["内存泄漏排查流程图", "images/memory-leak-runbook.svg"],
  ["频繁 Full GC 排查流程图", "images/full-gc-runbook.svg"],
  ["JMM 主内存与工作内存图", "images/jmm-memory-model.svg"],
];

const docPath = path.join(__dirname, "JVM面试实用学习文档.md");
let doc = fs.readFileSync(docPath, "utf8");
let index = 0;
doc = doc.replace(/```mermaid[\s\S]*?```/g, () => {
  const item = replacements[index++];
  if (!item) {
    throw new Error("Mermaid 图块数量超过替换图片数量");
  }
  return `![${item[0]}](${item[1]})`;
});

if (index !== replacements.length) {
  throw new Error(`替换数量不匹配：替换了 ${index} 个 Mermaid 图块，但准备了 ${replacements.length} 张图`);
}

fs.writeFileSync(docPath, doc, "utf8");
console.log(`已生成 ${replacements.length} 张 SVG 图片，并替换文档中的 Mermaid 图块。`);
