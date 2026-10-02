---
name: variables-lists
title: "变量与列表"
description: 变量的设置/改变/读取/显示,列表的增删改查、长度、包含判断,以及"变量是共享的、循环要用自己的计数器"这类规则。要记分数、计数、存一组数据时读。
---

# 变量

```
set [score v] to (0)
change [score v] by (1)
(score)                              // 读取:变量名放圆括号
show variable [score v]
hide variable [score v]
```
- 没有声明语句:第一次用到一个新名字就创建它,读到也算;和已有名字只差一两个字符的当拼错报错。没有任何脚本 `set` 过的变量永远是 0。
- 默认建成"适用于所有角色"的全局变量。要建成"仅适用于当前角色"的,在 write_script 里用 `localVariables: ["名字"]` 声明(列表用 `localLists`):每个克隆体会有自己的一份,各自的速度、血量、方向都要这样建;全局变量所有克隆体共用一个值。
- 变量不属于某个循环或某个脚本:同名就是同一个。两个可能同时运行的循环不要共用一个计数器,各用各的并在循环前归零。
- 初始值要写在帽子积木下面(事件里),不能放在脚本外。

# 列表

```
add [thing] to [items v]
delete (1) of [items v]              // (1) 也可以是 [last v] / [random v]
delete all of [items v]
insert [thing] at (1) of [items v]
replace item (1) of [items v] with [thing]
(item (1) of [items v])
(item # of [thing] in [items v])
(length of [items v])
<[items v] contains [thing]?>
show list [items v]
hide list [items v]
```
- 列表下标从 1 开始,范围是 1..length。
- 第一次用到的列表名自动创建,读到也算;没有任何脚本 `add` 过的列表永远是空的。
- 列表内容随作品保存:绿旗开始要先 `delete all of [items v]` 再 `add`,否则每次点绿旗都会越加越多。

## 片段(已编译通过)
计数循环(计数器自己归零):
```
when green flag clicked
set [i v] to (0)
repeat (10)
  change [i v] by (1)
  say (i) for (0.2) seconds
end
```

往列表里放 10 个随机数再逐个读:
```
when green flag clicked
delete all of [numbers v]
repeat (10)
  add (pick random (1) to (100)) to [numbers v]
end
set [k v] to (0)
repeat (length of [numbers v])
  change [k v] by (1)
  say (item (k) of [numbers v]) for (0.3) seconds
end
```

用两个列表记录一串坐标:
```
when green flag clicked
delete all of [xs v]
delete all of [ys v]
add (x position) to [xs v]
add (y position) to [ys v]
```
