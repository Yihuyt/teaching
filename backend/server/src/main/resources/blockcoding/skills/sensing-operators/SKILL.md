---
name: sensing-operators
title: "侦测与运算"
description: 碰到判断、按键与鼠标状态、询问与回答、计时器、距离,以及算术、比较、逻辑、随机数、字符串运算。写条件、算分数、做输入时读。
---

# 侦测

```
<touching [mouse-pointer v]?>        // mouse-pointer | edge | 角色名
<touching color [#ff0000]?>
<color [#ff0000] is touching [#00ff00]?>
(distance to [mouse-pointer v])      // mouse-pointer | 角色名;没有"到某坐标的距离",到坐标点的距离用 ([abs v] of ((x position) - (100))) 这类差值算
ask [你叫什么名字？] and wait
(answer)
<key [space v] pressed?>             // space | up arrow | down arrow | left arrow | right arrow | any | a ...
<mouse down?>
(mouse x)
(mouse y)
(loudness)
(timer)
reset timer
([x position v] of [Sprite1 v])     // x position | y position | direction | costume # | size | 变量名 ; 角色名或 stage
(current [YEAR v])                   // YEAR | MONTH | DATE | DAYOFWEEK | HOUR | MINUTE | SECOND
(days since 2000)
(username)
set drag mode [draggable v]          // draggable | not draggable
```

# 运算

```
((1) + (2))    ((3) - (1))    ((2) * (3))    ((6) / (2))
(pick random (1) to (10))
<(x) > (50)>   <(x) < (50)>   <(x) = (50)>
<<> and <>>    <<> or <>>    <not <>>
(join [hello] [world])
(letter (1) of [world])
(length of [world])
<[hello] contains [e]?>
((7) mod (3))
(round (3.5))
([abs v] of (-5))                    // abs | floor | ceiling | sqrt | sin | cos | tan | asin | acos | atan | ln | log | e ^ | 10 ^
```
- 每个运算符只接两个输入。三个数相加要两两嵌套:`(((a) + (b)) + (c))`。
- 比较和逻辑结果是尖括号积木,只能放进条件槽:`if <<(x) > (0)> and <(y) > (0)>> then`。
- 数字直接写 `(10)`,文字写 `[abc]`,读变量写 `(name)`。

## 片段(已编译通过)
碰到边缘就结束:
```
when green flag clicked
forever
  if <touching [edge v]?> then
    say [撞到了] for (1) seconds
    stop [all v]
  end
end
```

询问并比较回答:
```
when green flag clicked
set [secret v] to (pick random (1) to (100))
ask [猜一个数] and wait
if <(answer) > (secret)> then
  say [大了] for (2) seconds
else
  say [小了或对了] for (2) seconds
end
```

按住键持续移动(比按键帽子更顺滑):
```
when green flag clicked
forever
  if <key [right arrow v] pressed?> then
    change x by (5)
  end
  if <key [left arrow v] pressed?> then
    change x by (-5)
  end
end
```
