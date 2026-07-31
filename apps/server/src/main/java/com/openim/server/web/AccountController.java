package com.openim.server.web;

import com.openim.server.auth.AccountDeletionService;
import com.openim.server.auth.AuthPrincipal;
import com.openim.server.domain.Requests.DeleteAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 账号生命周期
 *
 * 单独一个控制器而不是塞进 {@link SessionController}：那里管的是会话
 * （登录状态的建立与撤销），这里管的是账号本身的存续。混在一起会让
 * "退出全部设备"和"注销账号"这两个后果完全不同的操作出现在同一组端点里，
 * 而它们的路径只差一个词。
 */
@Tag(name = "账号生命周期", description = "注销与个人数据删除")
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {
    private final AccountDeletionService accountDeletionService;
    private final ClientIpHasher clientIpHasher;

    public AccountController(
            AccountDeletionService accountDeletionService,
            ClientIpHasher clientIpHasher
    ) {
        this.accountDeletionService = accountDeletionService;
        this.clientIpHasher = clientIpHasher;
    }

    /**
     * 注销账号，履行个人信息保护法与 GDPR 的删除权。
     *
     * <b>不可逆。</b>需要提供当前密码——仅凭访问令牌就能销毁账号的话，
     * 令牌泄漏就等于账号被永久摧毁。这比改密的风险更高：改密可以再改回来。
     *
     * 实际执行的是匿名化而非物理删除。保留与清除的边界见
     * {@link AccountDeletionService} 的类注释——那个边界有法律含义，
     * 不是技术细节。
     *
     * 钱包余额不为零时返回 409：虚拟资产必须先由用户自己处置完，
     * 否则那笔钱注销后既不属于任何人、又还在账务总额里。
     */
    @DeleteMapping
    public ApiEnvelope.Success<Map<String, Object>> deleteAccount(
            @Valid @RequestBody DeleteAccount input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        accountDeletionService.deleteAccount(
                auth.userUuid(), input.password(), clientIpHasher.hashOf(request));
        return ApiEnvelope.success(
                Map.of("deleted", true, "anonymized", true), request);
    }
}
