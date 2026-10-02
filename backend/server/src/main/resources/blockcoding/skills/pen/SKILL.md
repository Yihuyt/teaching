---
name: pen
title: "画笔"
description: 落笔抬笔、清屏、图章、颜色与粗细,以及"移动才画线、先定位再落笔、只有 erase all 能擦"的规则。要画图形、画轨迹、画图表时读。
---

# 画笔

```
erase all
stamp
pen down
pen up
set pen color to [#ff0000]           // #rrggbb
change pen [color v] by (10)         // color | saturation | brightness | transparency (0-100)
set pen [color v] to (50)
change pen size by (1)
set pen size to (1)
```
- 落笔后**每个运动积木**都会在角色中心画线;`pen down` 本身画一个点。顺序:`pen up` → 定位 → `pen down` → 移动 → `pen up`。
- 画布是舞台大小的一张纸,所有角色共用,墨迹永久;唯一的橡皮是 `erase all`,绿旗脚本通常先 `erase all`。
- `move` 沿当前方向画;横线竖线用 `change x by` / `change y by` 或 `go to x: y:`。
- 画笔不能写字;要显示数值用 `show variable`。
- 需要重画的图(图表、动画):`erase all` 后整体重画一遍。
- 一个角色同一时刻只有一个位置、一支笔:两段脚本同时移动同一个角色画图,线会互相交错。`broadcast [x v]` 发出后当前脚本不等对方画完就往下走,还没画完时再发一次,对方那段会从头重来;要等画完再继续,用 `broadcast [x v] and wait`。
- 画图循环里的 `say [] for () seconds` 会把画图拖慢:10 根柱子各说 0.5 秒,一张图要 5 秒。
- 循环每转一圈刷新一帧(每秒 30 圈):`repeat (360)` 画一个圆要 12 秒。画圆用 `repeat (36)` + `move (17) steps` + `turn right (10) degrees` 就够圆,不到 2 秒;要每秒重画的图(时钟、图表),每次重画的循环总次数要少。

## 片段(已编译通过)
画正方形:
```
when green flag clicked
erase all
pen up
go to x:(-50) y:(-50)
point in direction (90)
pen down
repeat (4)
  move (100) steps
  turn left (90) degrees
end
pen up
```

彩色螺旋(线越来越长、颜色渐变):
```
when green flag clicked
erase all
pen up
go to x:(0) y:(0)
point in direction (90)
set pen size to (2)
set [len v] to (2)
pen down
repeat (80)
  move (len) steps
  turn right (30) degrees
  change [len v] by (2)
  change pen [color v] by (3)
end
pen up
```

画一个圆(36 段):
```
when green flag clicked
erase all
pen up
go to x:(0) y:(-100)
point in direction (90)
pen down
repeat (36)
  move (17) steps
  turn left (10) degrees
end
pen up
```

画一根竖条(用粗线):
```
when green flag clicked
erase all
pen up
go to x:(0) y:(-100)
set pen size to (20)
set pen color to [#3366ff]
pen down
change y by (80)
pen up
```
