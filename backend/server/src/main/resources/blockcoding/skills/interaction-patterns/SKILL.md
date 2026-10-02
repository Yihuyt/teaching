---
name: interaction-patterns
title: "交互套路"
description: 询问-回答式的问答程序(猜数字、出题练习)、按方向键移动、点击响应、广播协作。做问答、练习、需要键盘鼠标交互的程序时读。
---

# 交互套路

## 1. 猜数字(循环询问直到猜对)
```
when green flag clicked
set [secret v] to (pick random (1) to (100))
set [tries v] to (0)
repeat until <(answer) = (secret)>
  ask [猜一个 1 到 100 的数] and wait
  change [tries v] by (1)
  if <(answer) > (secret)> then
    say [大了] for (1) seconds
  else
    if <(answer) < (secret)> then
      say [小了] for (1) seconds
    end
  end
end
say (join [对了！你用了 ] (join (tries) [ 次])) for (3) seconds
```
`(answer)` 在第一次询问前是空的,所以 `repeat until` 第一次判断不会误停。

## 2. 连续出题、记分、把题目和回答存进列表
```
when green flag clicked
set [score v] to (0)
delete all of [questions v]
delete all of [answers v]
repeat (10)
  set [a v] to (pick random (10) to (99))
  set [b v] to (pick random (10) to (99))
  ask (join (a) (join [ + ] (join (b) [ = ?]))) and wait
  add (join (a) (join [ + ] (b))) to [questions v]
  add (answer) to [answers v]
  if <(answer) = ((a) + (b))> then
    change [score v] by (10)
    say [正确] for (1) seconds
  else
    say (join [答案是 ] ((a) + (b))) for (2) seconds
  end
end
say (join [总分: ] (score)) for (3) seconds
```

## 3. 方向键移动(每按一下走一步)
```
when [up arrow v] key pressed
change y by (10)
```
```
when [down arrow v] key pressed
change y by (-10)
```
```
when [left arrow v] key pressed
change x by (-10)
```
```
when [right arrow v] key pressed
change x by (10)
```

## 4. 方向键改变方向、定时前进(贪吃蛇式)
```
when [up arrow v] key pressed
point in direction (0)
```
```
when [right arrow v] key pressed
point in direction (90)
```
```
when green flag clicked
go to x:(0) y:(0)
point in direction (90)
forever
  move (20) steps
  wait (0.2) seconds
end
```

## 5. 广播协作:一个角色宣布,其他角色响应
```
when green flag clicked
wait (3) seconds
broadcast [start v]
```
```
when I receive [start v]
say [开始！] for (1) seconds
```
