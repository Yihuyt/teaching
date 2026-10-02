---
name: events-control
title: "事件与控制"
description: 帽子积木(绿旗、按键、点击、接收广播、克隆启动)、广播、等待、循环、条件、停止、克隆。写任何脚本的骨架时读。
---

# 事件与控制

## 帽子积木(每个脚本的第一行)
```
when green flag clicked
when [space v] key pressed           // space | up arrow | down arrow | left arrow | right arrow | a | 1 ...
when this sprite clicked
when backdrop switches to [backdrop1 v]
when [LOUDNESS v] > (10)             // LOUDNESS | TIMER
when I receive [message1 v]
when I start as a clone
```
帽子积木只有以上这些:**没有**"当变量等于某值"这种帽子。要在条件成立时做事,用 `wait until <>` 或 `forever` 里的 `if`。

## 广播
```
broadcast [message1 v]
broadcast [message1 v] and wait
```
`broadcast` 会启动所有角色里对应的 `when I receive`,不等它们结束;`and wait` 版本等它们跑完。广播不带数据:要传值就先设一个全局变量再广播。

## 控制
```
wait (1) seconds
wait until <>
repeat (10)
  ...
end
forever
  ...
end
if <> then
  ...
end
if <> then
  ...
else
  ...
end
repeat until <>
  ...
end
stop [all v]                         // all | this script | other scripts in sprite
create clone of [myself v]           // myself | 角色名
delete this clone
```
- 每个 C 形积木以单独一行 `end` 收尾,内容缩进两格。
- 没有 while / break / continue:用 `repeat until` 配相反条件,或 `stop [this script v]` 提前退出。
- `forever` 后面不能再放积木,它永远不结束。

## 片段(已编译通过)
按键控制(每个键一个脚本):
```
when [right arrow v] key pressed
change x by (10)
```
```
when [left arrow v] key pressed
change x by (-10)
```

游戏主循环:满足条件就结束:
```
when green flag clicked
set [lives v] to (3)
repeat until <(lives) < (1)>
  wait (1) seconds
end
say [游戏结束] for (2) seconds
stop [all v]
```

广播是所有角色**连同它们的克隆体**一起收到的:`when I receive` 里做"加关卡、重新摆砖、加分"这类只该发生一次的事,要放在没有克隆体的角色(如舞台或主角)里,否则有几个克隆体就重复几次。

克隆体各自运行:
```
when green flag clicked
hide
repeat (5)
  create clone of [myself v]
  wait (1) seconds
end
```
```
when I start as a clone
show
go to x:(pick random (-200) to (200)) y:(160)
repeat until <(y position) < (-160)>
  change y by (-5)
end
delete this clone
```
