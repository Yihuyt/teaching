/** 所有业务模块共用的内核:执行者与角色、业务异常、Web 契约、对象存储、课程内容引用与学情事件、长任务围栏与 SSE 推送。开放模块,子包都可被直接依赖 */
@org.springframework.modulith.ApplicationModule(
        displayName = "共享内核",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {}
)
package cn.utcy.teaching.shared;
