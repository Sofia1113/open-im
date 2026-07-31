package com.openim.server.auth;

import java.time.Duration;

/**
 * 密码重置令牌的送达方式
 *
 * 独立成接口是因为送达渠道是本项目<b>无法自带</b>的部分——发邮件需要 SMTP 或
 * SES/SendGrid 之类的外部服务，发短信需要运营商通道。把它抽象出来，
 * 使密码重置的其余部分（令牌生成、单次使用、过期、审计、限流、账号枚举防护）
 * 可以完整实现并测试，接入真实渠道时只需提供一个实现。
 *
 * <b>实现约定</b>：
 * <ul>
 *   <li>不得抛出异常。送达失败应记录日志并返回——
 *       让重置请求因为邮件服务抖动而返回 500，会把"邮箱是否注册过"
 *       通过响应状态码泄漏出去（成功的请求返回 200，失败的返回 500，
 *       而失败只发生在邮箱存在的情况下）。</li>
 *   <li>不得把令牌写入日志或指标。它等价于一次性密码。</li>
 * </ul>
 */
public interface PasswordResetNotifier {

    /**
     * 把重置令牌送达用户。
     *
     * @param email       目标邮箱，已确认非空
     * @param displayName 用于邮件称呼
     * @param token       明文令牌，<b>仅此一次出现</b>——服务端只存哈希
     * @param validFor    有效期，用于在邮件正文里告知用户
     */
    void sendResetToken(String email, String displayName, String token, Duration validFor);

    /**
     * 该实现是否可用于生产。
     *
     * 默认 false，使"忘记配置真实渠道"成为一个启动期错误而不是运行期的静默失效。
     * 真实渠道的实现覆盖为 true。
     */
    default boolean productionReady() {
        return false;
    }
}
