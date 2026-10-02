/**
 * teaching 平台通信桥：编辑器 iframe 与宿主(Vue)之间的唯一通道。
 *
 * 同源部署(编辑器经宿主 nginx 以 /scratch/ 反代)，因此双向都严格校验
 * event.origin === window.location.origin，postMessage 的 targetOrigin 同理。
 *
 * 编辑器保持最小职责：不做 XML→scratchblocks 文本转换(平台后端负责)、
 * 不弹确认对话框(宿主负责)、不发起任何网络请求。
 */
import ScratchBlocks from 'scratch-blocks';
import {vmInitialState as vm} from '../../reducers/vm';
import {
    CHANNEL, VERSION, KIND_REQ, KIND_RES, KIND_EVENT,
    EV_READY, EV_DIRTY, EV_BLOCK_DRAG, EV_BLOCKS_DRAGGED_OUT,
    REQ_ACK, REQ_EXPORT_SB3, REQ_LOAD_SB3, REQ_NEW_PROJECT, REQ_HARVEST, REQ_INSERT, REQ_REMOVE,
    REQ_RUN, REQ_STOP, REQ_READ_STATE, REQ_CREATE_SPRITE, REQ_SPRITES_HIDDEN,
    REQ_DELETE_SPRITE, REQ_DELETE_VARIABLE, REQ_CREATE_VARIABLE, REQ_HOST_OVERLAY
} from './protocol';

const DIRTY_THROTTLE_MS = 2000;
const SPRITE_SWITCH_TIMEOUT_MS = 2000;

const post = (message, transfer) => {
    window.parent.postMessage(
        {channel: CHANNEL, v: VERSION, ...message},
        window.location.origin,
        transfer
    );
};

const bridgeError = (code, message) => {
    const err = new Error(message);
    err.bridgeCode = code;
    return err;
};

/** 变量类型: 协议名 → scratch 内部类型 */
const VARIABLE_TYPES = {
    var: '',
    list: 'list',
    broadcast: 'broadcast_msg'
};

const variablesOfType = (target, type) =>
    Object.values(target.variables)
        .filter(v => (v.type || '') === type)
        .map(v => v.name);

const parseJsonArray = text => {
    try {
        const parsed = JSON.parse(text);
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
};

/**
 * 遍历一个 target 的积木表，取全部自定义积木签名。
 * argumentids 必须原样带上：编译器生成调用积木的 mutation 时要用它对齐
 * 工作区里已有定义的参数 id，缺了会造出接不上的孤儿调用。
 */
const proceduresOfTarget = target => {
    const procs = [];
    const blocks = target.blocks._blocks;
    for (const id of Object.keys(blocks)) {
        const block = blocks[id];
        if (block.opcode === 'procedures_prototype' && block.mutation && block.mutation.proccode) {
            procs.push({
                proccode: block.mutation.proccode,
                argumentnames: parseJsonArray(block.mutation.argumentnames || '[]'),
                argumentids: parseJsonArray(block.mutation.argumentids || '[]'),
                argumentdefaults: parseJsonArray(block.mutation.argumentdefaults || '[]')
            });
        }
    }
    return procs;
};

const harvestContext = () => {
    const stage = vm.runtime.getTargetForStage();
    if (!stage) throw bridgeError('not_ready', '编辑器尚未加载完成，请稍候重试');
    const sprites = [];
    const workspaceXml = {};
    const procedures = {};
    for (const target of vm.runtime.targets) {
        if (!target.isOriginal) continue;
        const name = target.getName();
        sprites.push({
            name,
            isStage: target.isStage,
            costumes: target.sprite.costumes.map(c => c.name),
            sounds: target.sprite.sounds.map(s => s.name),
            localVariables: target.isStage ? [] : variablesOfType(target, ''),
            localLists: target.isStage ? [] : variablesOfType(target, 'list')
        });
        workspaceXml[name] = `<xml>${target.blocks.toXML()}</xml>`;
        const procs = proceduresOfTarget(target);
        if (procs.length > 0) procedures[name] = procs;
    }
    return {
        sprites,
        globalVariables: variablesOfType(stage, ''),
        globalLists: variablesOfType(stage, 'list'),
        broadcasts: variablesOfType(stage, 'broadcast_msg'),
        procedures,
        currentSprite: vm.editingTarget ? vm.editingTarget.getName() : null,
        workspaceXml
    };
};

/**
 * 切换编辑目标。scratch-gui 的 Blocks 容器监听 vm 的 workspaceUpdate 事件、在事件里同步把新目标的积木装进工作区,
 * 而 setEditingTarget 是同步发这个事件的,所以在我们(后注册)的监听器里工作区已经换好了。
 * 不能用 requestAnimationFrame 等"下一帧":标签页在后台时浏览器不出帧,等一帧就是等到超时。
 */
const switchToSprite = spriteName => {
    const target = vm.runtime.targets.find(
        t => t.isOriginal && t.getName() === spriteName
    );
    if (!target) throw bridgeError('sprite_not_found', `找不到名为「${spriteName}」的角色`);
    if (vm.editingTarget && vm.editingTarget.id === target.id) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            vm.removeListener('workspaceUpdate', onUpdate);
            reject(bridgeError('sprite_switch_timeout', `切换到角色「${spriteName}」超时`));
        }, SPRITE_SWITCH_TIMEOUT_MS);
        const onUpdate = () => {
            if (!vm.editingTarget || vm.editingTarget.id !== target.id) return;
            clearTimeout(timer);
            vm.removeListener('workspaceUpdate', onUpdate);
            resolve();
        };
        vm.on('workspaceUpdate', onUpdate);
        vm.setEditingTarget(target.id);
    });
};

const findDefinitionByProccode = (workspace, proccode) =>
    workspace.getAllBlocks(false).find(block => {
        if (block.type !== 'procedures_definition') return false;
        const prototypeBlock = block.getChildren(false)
            .find(child => child.type === 'procedures_prototype');
        return prototypeBlock &&
            typeof prototypeBlock.getProcCode === 'function' &&
            prototypeBlock.getProcCode() === proccode;
    });

/** 内建可自动加载的扩展；xml 含其 opcode 前缀时插入前自动加载 */
const AUTO_LOAD_EXTENSIONS = ['pen', 'music'];

const ensureExtensionsLoaded = async xml => {
    for (const id of AUTO_LOAD_EXTENSIONS) {
        if (!xml.includes(`"${id}_`) && !xml.includes(`type="${id}_`)) continue;
        if (vm.extensionManager.isExtensionLoaded(id)) continue;
        await vm.extensionManager.loadExtensionURL(id);
    }
};

const insertBlocks = async payload => {
    const {xml, variables = [], replaceProcedures = [], targetSprite} = payload || {};
    if (!xml || typeof xml !== 'string') throw bridgeError('bad_request', '缺少要插入的积木 XML');
    await ensureExtensionsLoaded(xml);
    if (targetSprite) await switchToSprite(targetSprite);

    const workspace = ScratchBlocks.getMainWorkspace();
    if (!workspace) throw bridgeError('not_ready', '积木工作区尚未就绪');

    const createdVariables = [];
    for (const v of variables) {
        const type = VARIABLE_TYPES[v.type];
        if (type === undefined) throw bridgeError('bad_request', `未知的变量类型「${v.type}」`);
        if (!workspace.getVariable(v.name, type)) {
            workspace.createVariable(v.name, type, null, v.scope === 'local');
            createdVariables.push({name: v.name, type: v.type, scope: v.scope});
        }
    }

    // dispose(false):连同整段一起删。healStack=true 只删帽子,下面的积木会留成一段无帽子的孤儿
    for (const proccode of replaceProcedures) {
        const definition = findDefinitionByProccode(workspace, proccode);
        if (definition) definition.dispose(false);
    }

    const dom = ScratchBlocks.Xml.textToDom(xml);
    const metrics = workspace.getMetrics();
    const scale = workspace.scale || 1;
    const baseX = (metrics.viewLeft + 60) / scale;
    const baseY = (metrics.viewTop + 60) / scale;
    let offsetX = 0;
    const topBlockIds = [];
    for (const child of Array.from(dom.children)) {
        if (child.tagName.toLowerCase() !== 'block') continue;
        const block = ScratchBlocks.Xml.domToBlock(child, workspace);
        block.moveBy(baseX + offsetX, baseY);
        const size = block.getHeightWidth();
        offsetX += size.width + 40;
        topBlockIds.push(block.id);
    }
    if (typeof workspace.refreshToolboxSelection_ === 'function') {
        workspace.refreshToolboxSelection_();
    }
    return {insertedStacks: topBlockIds.length, topBlockIds, createdVariables};
};

/** 按顶层积木 id 删除整段脚本(连同其下所有积木);id 已不存在的跳过 */
const removeBlocks = async payload => {
    const {targetSprite, blockIds} = payload || {};
    if (!Array.isArray(blockIds)) throw bridgeError('bad_request', '缺少要删除的积木 id');
    if (targetSprite) await switchToSprite(targetSprite);
    const workspace = ScratchBlocks.getMainWorkspace();
    if (!workspace) throw bridgeError('not_ready', '积木工作区尚未就绪');
    let removed = 0;
    for (const id of blockIds) {
        const block = workspace.getBlockById(id);
        if (!block) continue;
        block.dispose(false);
        removed += 1;
    }
    return {removed};
};

const RUN_DEFAULT_WAIT_MS = 2000;
const RUN_MAX_WAIT_MS = 120000;
/** 非监视器线程数:判断作品"还在跑"用 */
const liveThreadCount = () =>
    vm.runtime.threads.filter(t => !t.updateMonitor).length;

/**
 * 点绿旗并观察一小段时间:作品自然停下(PROJECT_RUN_STOP)即 idle;
 * 到时仍有线程即 running(forever 类作品永远不会 idle,等太久只会拖住观察循环);
 * 既没停下也没线程,说明没有绿旗脚本被触发。
 */
const runProject = payload => {
    const requested = payload && Number.isFinite(payload.waitMs) ? payload.waitMs : RUN_DEFAULT_WAIT_MS;
    const waitMs = Math.max(0, Math.min(RUN_MAX_WAIT_MS, requested));
    return new Promise(resolve => {
        let settled = false;
        const finish = idle => {
            if (settled) return;
            settled = true;
            vm.runtime.removeListener('PROJECT_RUN_STOP', onStop);
            resolve({idle, running: !idle && liveThreadCount() > 0, threads: liveThreadCount(), waitedMs: waitMs});
        };
        const onStop = () => finish(true);
        vm.runtime.once('PROJECT_RUN_STOP', onStop);
        vm.greenFlag();
        setTimeout(() => finish(false), waitMs);
    });
};

const variableValues = (target, type) => {
    const out = {};
    for (const v of Object.values(target.variables)) {
        if ((v.type || '') === type) out[v.name] = v.value;
    }
    return out;
};

/** 舞台与各角色的运行态快照(克隆体只计数),给助教"看结果"用 */
/** 隐藏全部角色(记住原可见性)或恢复:截图只看画笔层。渲染在 VM 下一步自动刷新 */
let hiddenVisibility = null;
const setSpritesHidden = hidden => {
    if (hidden) {
        hiddenVisibility = new Map();
        for (const target of vm.runtime.targets) {
            if (target.isStage) continue;
            hiddenVisibility.set(target, target.visible);
            target.setVisible(false);
        }
    } else if (hiddenVisibility) {
        for (const [target, visible] of hiddenVisibility) {
            if (vm.runtime.targets.includes(target)) target.setVisible(visible);
        }
        hiddenVisibility = null;
    }
    vm.runtime.requestRedraw();
    return {};
};

const readState = () => {
    const stage = vm.runtime.getTargetForStage();
    if (!stage) throw bridgeError('not_ready', '编辑器尚未加载完成，请稍候重试');
    const clonesOf = {};
    for (const target of vm.runtime.targets) {
        if (target.isOriginal || target.isStage) continue;
        const name = target.sprite.name;
        clonesOf[name] = (clonesOf[name] || 0) + 1;
    }
    const sprites = [];
    for (const target of vm.runtime.targets) {
        if (!target.isOriginal || target.isStage) continue;
        const looks = target.getCustomState('Scratch.looks');
        sprites.push({
            name: target.getName(),
            x: Math.round(target.x * 100) / 100,
            y: Math.round(target.y * 100) / 100,
            direction: target.direction,
            visible: target.visible,
            size: target.size,
            costume: target.sprite.costumes[target.currentCostume] ?
                target.sprite.costumes[target.currentCostume].name : null,
            saying: looks && looks.text ? looks.text : null,
            clones: clonesOf[target.sprite.name] || 0,
            variables: variableValues(target, ''),
            lists: variableValues(target, 'list')
        });
    }
    return {
        backdrop: stage.sprite.costumes[stage.currentCostume] ?
            stage.sprite.costumes[stage.currentCostume].name : null,
        variables: variableValues(stage, ''),
        lists: variableValues(stage, 'list'),
        sprites,
        runningThreads: liveThreadCount()
    };
};

/**
 * 新建角色:复制第一个原生角色(带默认造型)再改名,复制来的脚本清空。
 * 同名角色已存在即幂等返回。并发的两次新建会抢到同一个复制体,因此排队串行执行。
 */
/** 改动作品的请求(建角色、插积木)排成一条队:并发执行会抢同一个复制体、或在切换角色时把积木插错地方 */
let mutationQueue = Promise.resolve();
const serialized = task => {
    const run = mutationQueue.then(task);
    mutationQueue = run.catch(() => undefined);
    return run;
};
const createSprite = payload => serialized(() => createSpriteNow(payload));

/** 角色不能叫这些:Stage 是舞台(桥按名字区分舞台),下划线包着的是下拉里的特殊值 */
const RESERVED_SPRITE_NAMES = new Set(['Stage', '_stage_', '_mouse_', '_random_', '_edge_', '_myself_']);

const createSpriteNow = async payload => {
    const name = payload && typeof payload.name === 'string' ? payload.name.trim() : '';
    if (!name) throw bridgeError('bad_request', '缺少角色名');
    if (RESERVED_SPRITE_NAMES.has(name)) throw bridgeError('bad_request', `角色不能叫「${name}」`);
    const existing = vm.runtime.targets.find(t => t.isOriginal && !t.isStage && t.getName() === name);
    if (existing) return {created: false, name};
    const template = vm.runtime.targets.find(t => t.isOriginal && !t.isStage);
    if (!template) throw bridgeError('no_template', '作品里没有可复制的角色');
    const before = new Set(vm.runtime.targets.map(t => t.id));
    await vm.duplicateSprite(template.id);
    const created = vm.runtime.targets.find(t => t.isOriginal && !t.isStage && !before.has(t.id));
    if (!created) throw bridgeError('internal', '复制角色失败');
    vm.renameSprite(created.id, name);
    if (created.getName() !== name) {
        // 编辑器改了名(重名会加序号):后面的脚本按请求的名字找角色会找不到,不如现在就说清楚
        vm.deleteSprite(created.id);
        throw bridgeError('bad_request', `角色名「${name}」不可用,编辑器把它改成了「${created.getName()}」`);
    }
    for (const blockId of Object.keys(created.blocks._blocks)) {
        created.blocks.deleteBlock(blockId);
    }
    vm.emitWorkspaceUpdate();
    return {created: true, name: created.getName()};
};

const deleteSpriteNow = async payload => {
    const name = payload && typeof payload.name === 'string' ? payload.name.trim() : '';
    if (!name) throw bridgeError('bad_request', '缺少角色名');
    const target = vm.runtime.targets.find(t => t.isOriginal && !t.isStage && t.getName() === name);
    if (!target) return {deleted: false, name};
    vm.deleteSprite(target.id);
    return {deleted: true, name};
};

/** 变量归谁:sprite 为空是全局的(挂在舞台上),否则是那个角色私有的 */
const variableOwner = sprite => {
    if (!sprite || sprite === 'Stage') return vm.runtime.getTargetForStage();
    const target = vm.runtime.targets.find(t => t.isOriginal && !t.isStage && t.getName() === sprite);
    if (!target) throw bridgeError('sprite_not_found', `找不到名为「${sprite}」的角色`);
    return target;
};

const deleteVariableNow = async payload => {
    const {sprite, name, list} = payload || {};
    if (!name) throw bridgeError('bad_request', '缺少变量名');
    const owner = variableOwner(sprite);
    const variable = owner.lookupVariableByNameAndType(name, list ? 'list' : '', true);
    if (!variable) return {deleted: false, name};
    owner.deleteVariable(variable.id);
    vm.emitWorkspaceUpdate();
    return {deleted: true, name};
};

const createVariableNow = async payload => {
    const {sprite, name, list} = payload || {};
    if (!name) throw bridgeError('bad_request', '缺少变量名');
    const owner = variableOwner(sprite);
    const type = list ? 'list' : '';
    if (!owner.lookupVariableByNameAndType(name, type, true)) {
        // 变量表在编辑目标的工作区上:切过去用 Blockly 建,和插积木时建变量走同一条路
        await switchToSprite(owner.getName());
        const workspace = ScratchBlocks.getMainWorkspace();
        if (!workspace) throw bridgeError('not_ready', '积木工作区尚未就绪');
        workspace.createVariable(name, type, null, !owner.isStage);
    }
    return {created: true, name};
};

const exportSb3 = async () => {
    const blob = await vm.saveProjectSb3();
    return blob.arrayBuffer();
};

/**
 * 宿主面板遮住的区域。scratch-blocks 判断"拖出工作区"只看指针是否在积木区矩形内,面板浮在积木区上面时
 * 指针在面板上仍算在工作区里,松手就是普通移动、不会通知宿主;所以把面板区域也算作工作区外,且不算删除区。
 */
let hostOverlay = null;
const pointerOnHostOverlay = e => Boolean(hostOverlay) &&
    e.clientX >= hostOverlay.x && e.clientX <= hostOverlay.x + hostOverlay.width &&
    e.clientY >= hostOverlay.y && e.clientY <= hostOverlay.y + hostOverlay.height;
const installHostOverlayHitTest = () => {
    const proto = ScratchBlocks.WorkspaceSvg.prototype;
    if (proto.teachingHostOverlayInstalled) return;
    proto.teachingHostOverlayInstalled = true;
    const isInsideBlocksArea = proto.isInsideBlocksArea;
    const isDeleteArea = proto.isDeleteArea;
    proto.isInsideBlocksArea = function (e) {
        return pointerOnHostOverlay(e) ? false : isInsideBlocksArea.call(this, e);
    };
    proto.isDeleteArea = function (e) {
        return pointerOnHostOverlay(e) ? false : isDeleteArea.call(this, e);
    };
};
const setHostOverlay = payload => {
    const rect = payload && payload.rect;
    if (!rect) {
        hostOverlay = null;
        return {};
    }
    const numbers = [rect.x, rect.y, rect.width, rect.height];
    if (!numbers.every(n => typeof n === 'number' && Number.isFinite(n))) {
        throw bridgeError('bad_request', 'rect 需要 x / y / width / height 四个数字');
    }
    hostOverlay = {x: rect.x, y: rect.y, width: rect.width, height: rect.height};
    return {};
};

export default function installTeachingBridge () {
    if (window.parent === window) return; // 非嵌入场景不装桥
    installHostOverlayHitTest();

    let defaultProjectBuffer = null; // 启动默认工程的快照，project/new 用它复位
    let lastDirtyAt = 0;
    let ackReceived = false;

    const captureDefaultProject = () => {
        exportSb3().then(buffer => {
            if (!defaultProjectBuffer) defaultProjectBuffer = buffer;
        });
    };

    const handlers = {
        [REQ_ACK]: () => {
            ackReceived = true;
            return {};
        },
        [REQ_EXPORT_SB3]: async () => {
            const buffer = await exportSb3();
            return {payload: buffer, transfer: [buffer]};
        },
        [REQ_LOAD_SB3]: async payload => {
            if (!payload || !(payload.buffer instanceof ArrayBuffer)) {
                throw bridgeError('bad_request', '缺少工程文件数据');
            }
            await vm.loadProject(payload.buffer);
            return {};
        },
        [REQ_NEW_PROJECT]: async () => {
            if (!defaultProjectBuffer) throw bridgeError('not_ready', '编辑器尚未加载完成，请稍候重试');
            await vm.loadProject(defaultProjectBuffer.slice(0));
            return {};
        },
        [REQ_HARVEST]: () => harvestContext(),
        [REQ_INSERT]: payload => serialized(() => insertBlocks(payload)),
        [REQ_REMOVE]: payload => serialized(() => removeBlocks(payload)),
        [REQ_RUN]: payload => runProject(payload),
        [REQ_STOP]: () => {
            vm.stopAll();
            return {};
        },
        [REQ_READ_STATE]: () => readState(),
        [REQ_CREATE_SPRITE]: payload => createSprite(payload),
        [REQ_DELETE_SPRITE]: payload => serialized(() => deleteSpriteNow(payload)),
        [REQ_DELETE_VARIABLE]: payload => serialized(() => deleteVariableNow(payload)),
        [REQ_CREATE_VARIABLE]: payload => serialized(() => createVariableNow(payload)),
        [REQ_SPRITES_HIDDEN]: payload => setSpritesHidden(Boolean(payload && payload.hidden)),
        [REQ_HOST_OVERLAY]: payload => setHostOverlay(payload)
    };

    window.addEventListener('message', event => {
        if (event.origin !== window.location.origin) return;
        const data = event.data;
        if (!data || data.channel !== CHANNEL || data.v !== VERSION || data.kind !== KIND_REQ) return;
        const handler = handlers[data.type];
        if (!handler) {
            post({kind: KIND_RES, id: data.id, type: data.type,
                error: {code: 'unknown_type', message: `不支持的桥消息类型「${data.type}」`}});
            return;
        }
        Promise.resolve()
            .then(() => handler(data.payload))
            .then(result => {
                const {payload, transfer} = (result && result.transfer) ?
                    result : {payload: result, transfer: undefined};
                post({kind: KIND_RES, id: data.id, type: data.type, payload}, transfer);
            })
            .catch(err => {
                post({kind: KIND_RES, id: data.id, type: data.type, error: {
                    code: err.bridgeCode || 'internal',
                    message: err.bridgeCode ? err.message : `编辑器内部错误：${err && err.message ? err.message : err}`
                }});
            });
    });

    // 把积木拖出工作区:宿主据此高亮对话框;松手时把那段积木(顶层 XML)交给宿主,编辑器自己会把它弹回原位
    let pointer = {x: 0, y: 0};
    const track = e => {
        pointer = {x: e.clientX, y: e.clientY};
    };
    document.addEventListener('pointermove', track, true);
    document.addEventListener('mousemove', track, true);
    vm.on('BLOCK_DRAG_UPDATE', outside => {
        post({kind: KIND_EVENT, type: EV_BLOCK_DRAG, payload: {outside: Boolean(outside), x: pointer.x, y: pointer.y}});
    });
    vm.on('BLOCK_DRAG_END', (blocks, topBlockId) => {
        const target = vm.editingTarget;
        if (!target || !target.blocks.getBlock(topBlockId)) return;
        post({kind: KIND_EVENT, type: EV_BLOCKS_DRAGGED_OUT, payload: {
            sprite: target.getName(),
            blockId: topBlockId,
            xml: `<xml>${target.blocks.blockToXML(topBlockId)}</xml>`,
            x: pointer.x,
            y: pointer.y
        }});
    });

    vm.on('PROJECT_CHANGED', () => {
        const now = Date.now();
        if (now - lastDirtyAt < DIRTY_THROTTLE_MS) return;
        lastDirtyAt = now;
        post({kind: KIND_EVENT, type: EV_DIRTY, payload: {dirty: true}});
    });

    const announceReady = () => {
        captureDefaultProject();
        const sendReady = () => {
            if (ackReceived) return;
            post({kind: KIND_EVENT, type: EV_READY, payload: {editorVersion: '5.3.0', vmReady: true}});
            // 宿主可能晚于编辑器就绪，未收到 ack 前每秒重播
            setTimeout(sendReady, 1000);
        };
        sendReady();
    };

    // 排障挂点：宿主/测试可从 iframe 里读桥的存活状态
    window.__teachingBridge = {
        installed: true,
        get targets() { return vm.runtime.targets.length; },
        get acked() { return ackReceived; }
    };

    // 默认工程加载完成后宣告就绪；若已错过事件则直接判定
    if (vm.runtime.targets.length > 0) {
        announceReady();
    } else {
        vm.runtime.once('PROJECT_LOADED', announceReady);
    }
}
