---
name: music
title: "音乐"
description: 打鼓、休止、弹奏音符、乐器与速度(音乐扩展)。要让作品演奏旋律或节拍时读。
---

# 音乐

## 写法
```
play drum [1 v] for (0.25) beats     // 鼓 1-18
rest for (0.25) beats
play note (60) for (0.25) beats      // 60 是中央 C,数字越大越高
set instrument to [1 v]              // 乐器 1-21
set tempo to (60)
change tempo by (20)
(tempo)
```

## 片段(已编译通过)
弹一段简单的旋律:
```
when green flag clicked
set instrument to [1 v]
set tempo to (120)
play note (60) for (0.5) beats
play note (64) for (0.5) beats
play note (67) for (0.5) beats
rest for (0.25) beats
play note (72) for (1) beats
```

打节拍:
```
when [space v] key pressed
repeat (4)
  play drum [1 v] for (0.25) beats
  play drum [2 v] for (0.25) beats
end
```
