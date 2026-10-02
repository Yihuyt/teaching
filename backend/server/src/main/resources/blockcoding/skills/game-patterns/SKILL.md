---
name: game-patterns
title: "游戏套路"
description: 小游戏的常见结构:得分与生命、倒计时、游戏结束、克隆出来的敌人/掉落物与碰撞计分、鼠标或键盘控制的玩家、重力跳跃。做接东西、打地鼠、飞翔小鸟、打砖块这类游戏时读。
---

# 游戏套路

每个套路都是几段独立脚本的组合,每段脚本一个代码块、放在它所属的角色里。

## 1. 记分与生命,游戏结束
主控脚本(通常放在玩家角色或舞台):
```
when green flag clicked
set [score v] to (0)
set [lives v] to (3)
show variable [score v]
show variable [lives v]
wait until <(lives) < (1)>
broadcast [game over v]
say [游戏结束] for (2) seconds
stop [all v]
```
其他角色收到广播后停下:
```
when I receive [game over v]
hide
stop [other scripts in sprite v]
```

## 2. 倒计时
```
when green flag clicked
set [time left v] to (30)
show variable [time left v]
repeat (30)
  wait (1) seconds
  change [time left v] by (-1)
end
say (join [得分: ] (score)) for (3) seconds
stop [all v]
```

## 3. 定时克隆出掉落物,克隆体自己下落、碰撞、计分
每个克隆体自己的速度这类值要建成仅当前角色的私有变量(write_script 的 `localVariables: ["speed"]`),全局变量所有克隆体共用一个值,后出生的会改掉先出生的。
本体只负责生产克隆:
```
when green flag clicked
hide
forever
  create clone of [myself v]
  wait (1) seconds
end
```
克隆体从顶部随机位置落下,碰到接的角色得分并消失,掉到底扣命:
```
when I start as a clone
go to x:(pick random (-220) to (220)) y:(170)
show
repeat until <<touching [Bowl v]?> or <(y position) < (-170)>>
  change y by (-4)
end
if <touching [Bowl v]?> then
  change [score v] by (1)
else
  change [lives v] by (-1)
end
delete this clone
```

## 4. 鼠标左右控制的玩家(y 固定)
```
when green flag clicked
go to x:(0) y:(-150)
forever
  set x to (mouse x)
end
```

## 5. 随机出现、点中得分(打地鼠)
```
when green flag clicked
forever
  go to x:(pick random (-200) to (200)) y:(pick random (-140) to (140))
  show
  wait (0.8) seconds
  hide
  wait (0.3) seconds
end
```
```
when this sprite clicked
change [score v] by (1)
hide
```

## 6. 重力与跳跃(飞翔小鸟)
```
when green flag clicked
go to x:(-100) y:(0)
set [vy v] to (0)
forever
  change [vy v] by (-1)
  change y by (vy)
  if <<touching [Pipe v]?> or <touching [edge v]?>> then
    broadcast [game over v]
    stop [this script v]
  end
end
```
```
when [space v] key pressed
set [vy v] to (10)
```

## 7. 从右往左移动的障碍克隆(管子)
```
when green flag clicked
hide
forever
  create clone of [myself v]
  wait (2) seconds
end
```
```
when I start as a clone
go to x:(240) y:(pick random (-100) to (100))
show
repeat until <(x position) < (-235)>
  change x by (-3)
end
change [score v] by (1)
delete this clone
```

## 8. 弹球
```
when green flag clicked
go to x:(0) y:(0)
point in direction (45)
forever
  move (8) steps
  if on edge, bounce
  if <touching [Paddle v]?> then
    turn right (180) degrees
    move (10) steps
  end
  if <(y position) < (-170)> then
    say [游戏结束] for (2) seconds
    stop [all v]
  end
end
```

## 9. 排成几行几列的克隆(砖块)
```
when green flag clicked
hide
set [row v] to (0)
repeat (3)
  set [col v] to (0)
  repeat (6)
    go to x:(((col) * (70)) - (175)) y:((150) - ((row) * (40)))
    create clone of [myself v]
    change [col v] by (1)
  end
  change [row v] by (1)
end
```
```
when I start as a clone
show
wait until <touching [Ball v]?>
change [score v] by (1)
delete this clone
```
