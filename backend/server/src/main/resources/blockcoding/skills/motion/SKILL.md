---
name: motion
title: "运动"
description: 移动、转向、定位、滑行、碰边反弹、旋转方式,以及 x/y/方向的读取。角色要动起来、跟随鼠标、朝某处走时读。
---

# 运动

## 写法
```
move (10) steps
turn right (15) degrees
turn left (15) degrees
go to [random position v]          // 目标: mouse-pointer | random position | 角色名
go to x:(0) y:(0)
glide (1) secs to [random position v]
glide (1) secs to x:(0) y:(0)
point in direction (90)             // 90 右 -90 左 0 上 180 下
point towards [mouse-pointer v]     // 目标: mouse-pointer | random direction | 角色名
change x by (10)
set x to (0)
change y by (10)
set y to (0)
if on edge, bounce
set rotation style [left-right v]   // left-right | don't rotate | all around
(x position)
(y position)
(direction)
```
舞台坐标:x 在 -240..240,y 在 -180..180,(0,0) 是正中央。`move` 沿当前方向走,水平/垂直位移用 `change x by` / `change y by` 更直接。

## 片段(已编译通过)
来回走动、碰边反弹、不倒立:
```
when green flag clicked
set rotation style [left-right v]
forever
  move (10) steps
  if on edge, bounce
end
```

回到起点并朝右:
```
when green flag clicked
go to x:(-200) y:(0)
point in direction (90)
```

一直跟着鼠标:
```
when green flag clicked
forever
  point towards [mouse-pointer v]
  move (5) steps
end
```
