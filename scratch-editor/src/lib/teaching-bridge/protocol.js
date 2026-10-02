/**
 * teaching-scratch-bridge 协议常量。
 * 信封: {channel, v, id, kind: "req"|"res"|"event", type, payload, error?}
 * error: {code, message}，message 为中文，宿主可直接展示。
 * 宿主(teaching 前端)侧持有同构的一份类型定义(shared/scratchBridge.ts)，
 * 两侧必须同步修改。
 */
export const CHANNEL = 'teaching-scratch-bridge';
export const VERSION = 1;

export const KIND_REQ = 'req';
export const KIND_RES = 'res';
export const KIND_EVENT = 'event';

// editor → host 事件
export const EV_READY = 'bridge/ready';
export const EV_DIRTY = 'project/dirty';
// 用户把一段积木拖出了工作区 / 拖回来了。payload {outside, x, y}(x/y 是指针在编辑器视口里的坐标)
export const EV_BLOCK_DRAG = 'blocks/drag';
// 用户把一段积木拖出工作区后松手(积木会弹回原位)。payload {sprite, blockId, xml, x, y}
export const EV_BLOCKS_DRAGGED_OUT = 'blocks/dragged-out';

// host → editor 请求
export const REQ_ACK = 'bridge/ack';
export const REQ_EXPORT_SB3 = 'project/export-sb3';
export const REQ_LOAD_SB3 = 'project/load-sb3';
export const REQ_NEW_PROJECT = 'project/new';
export const REQ_HARVEST = 'context/harvest';
export const REQ_INSERT = 'blocks/insert';
// 删除若干段脚本(按顶层积木 id)。payload {targetSprite, blockIds: string[]}
export const REQ_REMOVE = 'blocks/remove';
export const REQ_RUN = 'project/run';
export const REQ_STOP = 'project/stop';
export const REQ_READ_STATE = 'project/read-state';
export const REQ_CREATE_SPRITE = 'sprite/create';
/** 删角色(连同脚本、造型、声音):{name} */
export const REQ_DELETE_SPRITE = 'sprite/delete';
/** 删变量或列表:{sprite|null, name, list};sprite 为空是全局的 */
export const REQ_DELETE_VARIABLE = 'variable/delete';
/** 建变量或列表:{sprite|null, name, list};回退时把删掉的建回来 */
export const REQ_CREATE_VARIABLE = 'variable/create';
// 评测用:隐藏/恢复全部角色,截图只剩画笔层与背景。payload {hidden: boolean}
export const REQ_SPRITES_HIDDEN = 'stage/sprites-hidden';
/** 宿主浮在编辑器上面的面板(助手对话框)所占区域,编辑器视口坐标:{rect: {x, y, width, height} | null}。
 *  积木拖到这块区域上算"拖出工作区"(松手弹回原位并发 blocks/dragged-out),不算删除区,面板遮住哪里都能接 */
export const REQ_HOST_OVERLAY = 'host/overlay';
