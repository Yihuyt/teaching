---
name: looks-sound
title: "外观与声音"
description: 说话/思考气泡、显示隐藏、造型与背景切换、特效、大小、图层,以及播放声音、音量。要让角色说话、换造型、出声音时读。
---

# 外观与声音

## 写法
```
say [Hello!] for (2) seconds
say [Hello!]
think [Hmm...] for (2) seconds
think [Hmm...]
show
hide
switch costume to [costume1 v]
next costume
switch backdrop to [backdrop1 v]
switch backdrop to [backdrop1 v] and wait
next backdrop
change [color v] effect by (25)     // color | fisheye | whirl | pixelate | mosaic | brightness | ghost
set [color v] effect to (0)
clear graphic effects
change size by (10)
set size to (100)%
go to [front v] layer               // front | back
go [forward v] (1) layers           // forward | backward
(size)
(costume [number v])                // number | name
(backdrop [number v])
start sound [Meow v]
play sound [Meow v] until done
stop all sounds
change volume by (-10)
set volume to (100)%
(volume)
```
`say (join [得分: ] (score))` 可以把文字和数值拼在一起说出来。`say []`(空文本)用来清掉气泡。

## 片段(已编译通过)
点击角色换造型并打招呼:
```
when this sprite clicked
next costume
say [你好！] for (2) seconds
```

说出变量的值:
```
when green flag clicked
set [score v] to (0)
say (join [得分: ] (score)) for (2) seconds
```

出现时播放声音:
```
when green flag clicked
show
start sound [Meow v]
```
