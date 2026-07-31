package com.openim.server.domain;

import java.util.Optional;

/**
 * 群成员角色与权限矩阵
 *
 * {@code conversation_members.role} 自建表起就有 MEMBER/ADMIN/OWNER 三个取值，
 * 但在本次改造之前<b>没有一行代码读它</b>——群里谁都不能加人、踢人、改群名，
 * 因为这些操作根本不存在。这个枚举是那套取值第一次真正生效的地方。
 *
 * <b>为什么权限判断集中在这里而不散在 Service 里</b>：权限矩阵的每一格都是
 * 一个可以单独出错的判断，散成一串 if 之后"ADMIN 能不能踢 ADMIN"这种问题
 * 只能靠通读代码回答，而漏掉一格的后果是越权。集中成方法后，矩阵可以被
 * 逐格断言（见 ConversationMembershipIntegrationTest 的权限矩阵测试）。
 *
 * 单聊<b>不</b>使用这套角色：它没有管理者，两个成员都是 MEMBER，
 * 且不允许加人、踢人、转让。相关校验在 ConversationService 里以会话类型为准，
 * 不依赖角色——给单聊成员发一个 OWNER 角色再去判断权限，等于给单聊
 * 凭空造出一个群主。
 */
public enum MemberRole {
    /** 普通成员：只能读写消息和退群 */
    MEMBER,

    /** 管理员：可加人、可移出普通成员，不能改角色、不能动其他管理员和群主 */
    ADMIN,

    /** 群主：全部权限。每个群恰好一个，转让时原群主降为 ADMIN */
    OWNER;

    /** 解析数据库中的取值。未知取值返回空而不是抛异常——调用方按"无权限"处理 */
    public static Optional<MemberRole> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** 能否邀请新成员 */
    public boolean canAddMembers() {
        return this == OWNER || this == ADMIN;
    }

    /** 能否修改群名称等资料 */
    public boolean canEditProfile() {
        return this == OWNER || this == ADMIN;
    }

    /** 能否指派或撤销管理员。仅群主——否则管理员可以自我提权或互相夺权 */
    public boolean canAssignRoles() {
        return this == OWNER;
    }

    /** 能否转让群主。仅群主 */
    public boolean canTransferOwnership() {
        return this == OWNER;
    }

    /**
     * 能否移出某个角色的成员。
     *
     * <ul>
     *   <li>群主可以移出管理员和普通成员</li>
     *   <li>管理员只能移出普通成员——否则两个管理员可以互踢，
     *       结果取决于谁的请求先到，这不是权限模型该有的行为</li>
     *   <li><b>任何人都不能移出群主</b>，包括群主自己。
     *       群主要退群必须先转让，否则会留下一个没有管理者的群</li>
     * </ul>
     */
    public boolean canRemove(MemberRole target) {
        if (target == OWNER) {
            return false;
        }
        return switch (this) {
            case OWNER -> true;
            case ADMIN -> target == MEMBER;
            case MEMBER -> false;
        };
    }

    /**
     * 能否主动退群。
     *
     * 群主不能直接退出：群会失去唯一的管理者，此后没人能加人、改名或指派管理员。
     * 转让给他人后即可退出。
     */
    public boolean canLeave() {
        return this != OWNER;
    }
}
