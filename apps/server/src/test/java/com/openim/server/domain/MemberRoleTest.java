package com.openim.server.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 群成员权限矩阵
 *
 * <b>为什么值得一个独立的测试类</b>：权限矩阵的每一格都是一个可以单独出错的判断，
 * 而漏掉一格的后果是越权。集成测试会覆盖其中的主要路径，但它每验证一格就要
 * 建一个群、加几个人、发一次请求——真按矩阵逐格覆盖会慢到没人愿意跑。
 *
 * 这里逐格断言，不碰数据库；集成测试只需验证"服务确实按这个矩阵判断"。
 */
class MemberRoleTest {

    @Test
    void ownerCanDoEverything() {
        MemberRole owner = MemberRole.OWNER;

        assertThat(owner.canAddMembers()).isTrue();
        assertThat(owner.canEditProfile()).isTrue();
        assertThat(owner.canAssignRoles()).isTrue();
        assertThat(owner.canTransferOwnership()).isTrue();
        assertThat(owner.canRemove(MemberRole.ADMIN)).isTrue();
        assertThat(owner.canRemove(MemberRole.MEMBER)).isTrue();
    }

    /**
     * 管理员能加人、能踢普通成员，但不能碰角色也不能踢管理员。
     *
     * "不能踢管理员"这一格特别容易被写成"能踢除群主之外的所有人"——
     * 那样两个管理员可以互踢，结果取决于谁的请求先到。
     */
    @Test
    void adminCanManageMembersButNotRoles() {
        MemberRole admin = MemberRole.ADMIN;

        assertThat(admin.canAddMembers()).isTrue();
        assertThat(admin.canEditProfile()).isTrue();
        assertThat(admin.canRemove(MemberRole.MEMBER)).isTrue();

        assertThat(admin.canAssignRoles()).as("管理员不能自我提权或互相夺权").isFalse();
        assertThat(admin.canTransferOwnership()).isFalse();
        assertThat(admin.canRemove(MemberRole.ADMIN)).as("管理员不能互踢").isFalse();
    }

    @Test
    void memberCanOnlyReadWriteAndLeave() {
        MemberRole member = MemberRole.MEMBER;

        assertThat(member.canAddMembers()).isFalse();
        assertThat(member.canEditProfile()).isFalse();
        assertThat(member.canAssignRoles()).isFalse();
        assertThat(member.canTransferOwnership()).isFalse();
        assertThat(member.canRemove(MemberRole.MEMBER)).isFalse();
        assertThat(member.canLeave()).isTrue();
    }

    /**
     * 谁都不能移出群主——包括群主自己。
     *
     * 允许移出群主会留下一个没有管理者的群：此后没人能加人、改名或指派管理员，
     * 而修复它需要直接改数据库。
     */
    @Test
    void nobodyCanRemoveTheOwner() {
        for (MemberRole actor : MemberRole.values()) {
            assertThat(actor.canRemove(MemberRole.OWNER))
                    .as("%s 不应能移出群主", actor)
                    .isFalse();
        }
    }

    /** 群主必须先转让才能退群，否则同样留下无主的群 */
    @Test
    void ownerCannotLeaveWithoutTransferring() {
        assertThat(MemberRole.OWNER.canLeave()).isFalse();
        assertThat(MemberRole.ADMIN.canLeave()).isTrue();
        assertThat(MemberRole.MEMBER.canLeave()).isTrue();
    }

    /**
     * 未知取值按"无角色"处理而不是抛异常。
     *
     * 数据库的 CHECK 约束保证不会出现未知取值，但如果将来新增了一个角色而这段
     * 代码没跟上，抛异常会让整个会话不可访问；返回空则由调用方按"权限不足"处理，
     * 影响面小得多。
     */
    @Test
    void unknownRoleValuesParseToEmpty() {
        assertThat(MemberRole.parse("OWNER")).contains(MemberRole.OWNER);
        assertThat(MemberRole.parse("SUPER_ADMIN")).isEmpty();
        assertThat(MemberRole.parse("owner")).as("取值区分大小写，与数据库一致").isEmpty();
        assertThat(MemberRole.parse(null)).isEmpty();
    }
}
