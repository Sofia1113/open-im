package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.Requests.CreateGroupConversation;
import com.openim.server.domain.repository.ConversationRepository;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 群成员上限
 *
 * 上限压到 3 人来测，因为按生产默认值（500）测需要建 500 个用户，慢到没人愿意跑，
 * 于是这条约束就会变成"写了但从没验证过"。上限是<b>配置项</b>而不是编译期常量，
 * 正是为了让它可以被这样验证——同时也让部署方能按自己压测出的容量收紧。
 *
 * <b>为什么上限必须在服务端按"当前人数 + 本次新增"计算</b>：
 * 请求体上的 {@code @Size} 只挡住单次请求带太多人的情况，客户端分十次各加 50 个人
 * 就能绕过它。逐个加人时各自检查一遍上限同样不够——那样一次带 100 人的请求
 * 会在超限后才停下，前面的人已经写进去了。
 */
@SpringBootTest(properties = {
        "openim.conversation.max-group-members=3"
})
class GroupMemberLimitIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void theConfiguredLimitIsInEffect() {
        assertThat(conversationService.maxGroupMembers()).isEqualTo(3);
    }

    /** 建群时按"邀请人数 + 创建者"计算 */
    @Test
    void creatingAGroupBeyondTheLimitIsRejected() {
        UUID owner = createUser();
        List<String> invitees = createUsers(3);

        assertThatThrownBy(() -> conversationService.createGroupConversation(owner,
                new CreateGroupConversation("超限群", null, invitees)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能超过 3 人");

        // 刚好到上限可以建
        Conversation group = conversationService.createGroupConversation(owner,
                new CreateGroupConversation("满员群", null, invitees.subList(0, 2)));
        assertThat(conversationRepository.countActiveMembers(UUID.fromString(group.id()))).isEqualTo(3);
    }

    /**
     * 加人时按"当前人数 + 本次新增"整批判断，超限则整批拒绝。
     *
     * 部分成功是更糟的行为：调用方拿到一个错误，却不知道有几个人已经加进去了，
     * 重试会得到不同的结果。
     */
    @Test
    void addingMembersBeyondTheLimitRejectsTheWholeBatch() {
        UUID owner = createUser();
        List<String> invitees = createUsers(1);
        Conversation group = conversationService.createGroupConversation(owner,
                new CreateGroupConversation("待扩容群", null, invitees));
        UUID id = UUID.fromString(group.id());

        // 当前 2 人，上限 3，一次加 2 个人应整批失败
        List<String> more = createUsers(2);
        assertThatThrownBy(() -> conversationService.addMembers(id, owner, more))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能超过 3 人");

        assertThat(conversationRepository.countActiveMembers(id))
                .as("整批拒绝，不该有人被加进去")
                .isEqualTo(2);

        // 加一个人正好到上限
        conversationService.addMembers(id, owner, more.subList(0, 1));
        assertThat(conversationRepository.countActiveMembers(id)).isEqualTo(3);
    }

    /**
     * 退群腾出的名额可以被重新使用。
     *
     * 上限按 {@code left_at IS NULL} 计数而不是按行数——软删的成员行还在表里，
     * 若按行数计算，一个群在几次进出之后就再也加不进人了。
     */
    @Test
    void leavingFreesUpASlot() {
        UUID owner = createUser();
        List<String> invitees = createUsers(2);
        Conversation group = conversationService.createGroupConversation(owner,
                new CreateGroupConversation("满员群", null, invitees));
        UUID id = UUID.fromString(group.id());

        UUID leaver = UUID.fromString(invitees.get(0));
        conversationService.leave(id, leaver);

        UUID newcomer = createUser();
        conversationService.addMembers(id, owner, List.of(newcomer.toString()));

        assertThat(conversationRepository.countActiveMembers(id)).isEqualTo(3);
        assertThat(conversationRepository.findActiveMemberRole(id, newcomer)).contains("MEMBER");
    }

    private List<String> createUsers(int count) {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ids.add(createUser().toString());
        }
        return ids;
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO users (id, username, display_name, avatar, status)
                        VALUES (:id, :username, '上限测试', '限', 'ACTIVE')
                        """)
                .param("id", id)
                .param("username", "limit-" + id.toString().substring(0, 12))
                .update();
        return id;
    }
}
