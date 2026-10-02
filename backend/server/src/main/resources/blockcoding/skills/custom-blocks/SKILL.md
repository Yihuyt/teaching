---
name: custom-blocks
title: "自定义积木"
description: 用 define 定义带参数的自定义积木并调用它。同一段逻辑要在多处复用、或想把长脚本拆成几步时读。
---

# 自定义积木

```
define draw square (size)
...
```
- `define` 行是帽子:词与参数可以交错,数字/文字参数用 `(name)`,布尔参数用 `<name>`。
- 调用时写同样的词,参数槽填值:`draw square (100)`。
- 参数在定义里读作 `(size)`,只读;定义里用到的其他名字都是共享变量。
- 定义和调用要在**同一个角色**里,而且**先写定义脚本、再写调用它的脚本**(调用一个还没定义的积木会编译失败)。
- 同一个自定义积木只定义一次;要改就重新写整段 define,旧定义会被替换。

## 片段(已编译通过)
定义与调用:
```
define draw square (size)
pen down
repeat (4)
  move (size) steps
  turn left (90) degrees
end
pen up
```
```
when green flag clicked
erase all
go to x:(-50) y:(-50)
point in direction (90)
draw square (100)
```

带两个参数:
```
define jump (height) for (time)
change y by (height)
wait (time) seconds
change y by ((0) - (height))
```
```
when [space v] key pressed
jump (50) for (0.3)
```
