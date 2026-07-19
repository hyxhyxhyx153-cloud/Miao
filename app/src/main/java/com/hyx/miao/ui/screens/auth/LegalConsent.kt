package com.hyx.miao.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyx.miao.data.remote.api.AppApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LegalDocuments(
    val privacyPolicy: String = FALLBACK_PRIVACY_POLICY,
    val userAgreement: String = FALLBACK_USER_AGREEMENT,
    val version: String = "内置 2026.07.16",
)

enum class LegalDocumentType {
    UserAgreement,
    PrivacyPolicy,
}

@HiltViewModel
class LegalViewModel @Inject constructor(
    private val appApi: AppApi,
) : ViewModel() {
    private val _documents = MutableStateFlow(LegalDocuments())
    val documents = _documents.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { appApi.getLegalDocuments() }
                .onSuccess { response ->
                    _documents.value = LegalDocuments(
                        privacyPolicy = response.privacyPolicy
                            ?.takeIf { it.isNotBlank() }
                            ?: FALLBACK_PRIVACY_POLICY,
                        userAgreement = response.userAgreement
                            ?.takeIf { it.isNotBlank() }
                            ?: FALLBACK_USER_AGREEMENT,
                        version = response.version
                            ?.takeIf { it.isNotBlank() }
                            ?: "内置 2026.07.16",
                    )
                }
        }
    }
}

@Composable
fun LegalConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenDocument: (LegalDocumentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(
            modifier = Modifier.padding(top = 5.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                "已阅读并同意",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onOpenDocument(LegalDocumentType.UserAgreement) },
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    modifier = Modifier.heightIn(min = 32.dp),
                ) { Text("《用户协议》", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "和",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onOpenDocument(LegalDocumentType.PrivacyPolicy) },
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    modifier = Modifier.heightIn(min = 32.dp),
                ) { Text("《隐私政策》", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
fun LegalDocumentDialog(
    type: LegalDocumentType,
    documents: LegalDocuments,
    onDismiss: () -> Unit,
) {
    val title = when (type) {
        LegalDocumentType.UserAgreement -> "用户协议"
        LegalDocumentType.PrivacyPolicy -> "隐私政策"
    }
    val content = when (type) {
        LegalDocumentType.UserAgreement -> documents.userAgreement
        LegalDocumentType.PrivacyPolicy -> documents.privacyPolicy
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "版本：${documents.version}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = content,
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private const val FALLBACK_PRIVACY_POLICY = """Miao 隐私政策（离线摘要）

版本：2026.07.16
发布日期及生效日期：2026年7月16日

生效说明：当前无法连接服务器，因此显示内置摘要。恢复联网后，请在登录或注册页打开服务端发布的《Miao 隐私政策（个人信息处理规则）》全文；全文包含逐项数据清单、第三方清单、跨境、保存期限、权利和未成年人规则，并以你确认的最新版本为准。

【运营者上线前必须填写】
个人信息处理者/运营者：【填写真实完整名称或姓名】
注册地址和常用办公地址：【填写】
个人信息保护负责人及专用邮箱：【填写】
客服电话、服务时间及未成年人保护渠道：【填写】

上述信息未据实填写时，不足以完成法定告知，实际运营者不应开始对公众收集个人信息。

一、核心数据处理
1. 账号：处理用户名、邮箱、密码哈希、账号状态和登录令牌，用于注册、登录、同步和安全控制。原始密码不以明文保存；选择“记住我”时，Android 使用系统密钥支持的加密存储保存令牌。
2. 聊天：保存会话、文字、图片 URL、模型回复、人格、情绪和时间。生成回复时，当前消息、约定数量的历史消息、人格系统提示词、最多若干条相关记忆和你选择的图片可能发送给后台启用的大模型或兼容接口。
3. 记忆：保存你手动添加或系统自动提取的长期偏好、称呼和约束及其向量。系统会过滤部分明显密码、证件、银行卡、手机号、邮箱、精确地址和第三方事实，但不能保证识别全部敏感信息。语义检索启用时，记忆或查询词可能发送给嵌入模型接口。
4. 人格和图片：保存人格名称、简介、系统提示词及一至三张参考图片。生图时，需求和完善后的提示词会发送给图片模型；涉及人格时，参考图片原始文件也可能发送给图片编辑接口。生成结果保存到运营者本地或对象存储。
5. 微信通道：扫码绑定时处理加密 bot token、微信侧 bot/user 标识、通道状态和文字消息，并通过微信 iLink/ClawBot 或兼容地址收发；消息还会交给所选 AI 模型。解绑仅停止通道，不等于删除既有绑定和聊天记录。
6. 邮件和语音：找回密码时，邮箱及一次性链接会交给运营者配置的 SMTP 服务。语音输入由设备安装的 Android 语音识别服务处理原始语音，Miao 通常只接收转写文字。
7. 本地数据：设置保存在 DataStore，会话等缓存保存在 Room。Android 备份规则排除认证令牌和聊天数据库；其他非内容偏好仍可能由系统按你的备份设置处理。

二、服务日志
为配额、安全和排障，服务端可能记录用户 ID、用户名、邮箱、会话和模型信息、请求前两千字、回复前四千字、token 用量、耗时、状态和错误。模型请求日志按当前规则保存七天，仅授权管理员可查看。管理员操作审计日志、访问日志和媒体文件的具体期限必须由运营者在完整版政策中填写并配置清理机制。

三、敏感个人信息
一般聊天不需要身份证、金融账户、精确位置、病历、人脸生物特征、私密关系或私密影像。请不要主动提交无关敏感信息或他人信息。处理敏感个人信息、向其他个人信息处理者提供、公开或向境外提供时，登录页的总括勾选不能替代法律要求的专门告知和单独同意。

四、第三方和跨境
代码可能接入 Anthropic、OpenAI、DeepSeek、通义千问/阿里云、智谱 AI、GPT Image/Image 2.0、运营者自定义兼容代理、微信 iLink/ClawBot、对象存储、SMTP、云主机、数据库、缓存、反向代理及 Android 系统服务。实际运营者必须在完整版中仅保留真实启用项，并填写接收方完整名称、联系方式、服务器地区和保存期限。

若模型、接口、服务器或远程访问人员位于中国境外，可能构成个人信息出境。运营者必须事前告知境外接收方名称、联系方式、目的、方式、信息种类、保存期限和行权方式，取得单独同意，开展影响评估并完成适用的安全评估、标准合同备案、认证或其他法定程序；未完成时不得启用境外链路。

五、保存和删除现状
账号、聊天、人格、记忆和设置通常保存到你删除、注销账号、服务终止或目的实现。当前客户端支持删除单条消息、会话和人格；记忆删除在服务端可能是停用/软删除；删除会话、消息、人格或解绑微信不保证同步物理删除对象存储、本地上传文件、备份或第三方副本。当前普通用户没有完整自助注销入口，应通过运营者专用渠道申请；运营者必须上线便捷注销、媒体生命周期、备份过期和第三方删除流程。

六、权限和安全
网络权限用于 API、媒体、公告和更新。图片主要通过系统照片/文件选择器由你逐项选择，不持续扫描相册；你可拒绝媒体权限并继续使用文字功能。正式公网服务必须使用 HTTPS；若服务器地址是非可信环境中的 http://，请不要提交账号、聊天或图片。正式 Android 包关闭网络正文日志，调试包只保留基础请求信息并隐藏 Authorization 请求头。

七、你的权利
你依法享有知情、决定、限制或拒绝处理、查阅、复制、转移、更正、补充、删除、撤回同意、注销账号、要求解释以及有关自动化决定的权利。应用内无法完成的，请通过运营者专用邮箱提出。运营者应核验身份后及时处理，原则上不超过十五个工作日；拒绝时说明理由。你还可向有权监管部门投诉或依法寻求司法救济。

八、人工智能和未成年人
你正在与人工智能而非真人互动。输出可能错误、偏差或不适宜，不构成医疗、心理、法律或金融意见，也不具备紧急救助能力。人格和情绪表达是模拟；生成内容应依法添加和保留 AI 标识。

本服务当前仅面向年满十八周岁用户。未满十八周岁者不得注册或使用。运营者应在注册和登录环节落实有效年龄门槛；当前项目尚未形成完整年龄核验闭环，在上线年龄门槛前不应向公众开放注册。若监护人发现未成年人已提交信息，可要求停止处理和删除。

九、联系与更新
运营者必须在本摘要首页和完整版中提供真实可达的个人信息保护渠道。涉及目的、方式、信息种类、敏感信息、接收方、跨境、未成年人或用户权利的实质变更，应显著提示并依法重新取得同意，不能仅以继续使用推定同意。"""

private const val FALLBACK_USER_AGREEMENT = """Miao 用户服务协议（离线摘要）

版本：2026.07.16
发布日期及生效日期：2026年7月16日

生效说明：当前无法连接服务器，因此显示内置摘要。恢复联网后，请在登录或注册页打开服务端发布的《Miao 用户服务协议》全文。全文包含账号、AI、生图标识、人格/参考图、用户内容授权、禁止行为、未成年人、服务管理、责任边界和争议解决规则，并以你确认的最新版本为准。

【运营者上线前必须填写】
服务提供者/运营者：【填写真实完整名称或姓名】
注册地址和常用办公地址：【填写】
客服邮箱、电话及服务时间：【填写】
违法和不良信息、知识产权/人格权及未成年人投诉渠道：【填写】

上述信息未据实填写时，实际运营者不应据此对公众提供服务。

一、协议与服务
本协议适用于 Miao Android 客户端、API 及聊天、人格、记忆、图片生成、微信通道、公告和升级服务，并与《Miao 隐私政策（个人信息处理规则）》及具体功能说明共同组成约定。第三方模型、微信、语音识别、对象存储和应用商店可能另有规则，但不免除运营者依法应承担的责任。

二、人工智能特别提示
你正在与 AI 而非真人互动。人格名称、头像、情绪、动作和陪伴表达均为模拟，不代表真实意识、情感、资质或现实关系。AI 可能产生错误、过时、虚构、偏差或不适宜内容，不构成医疗、心理、法律、投资或其他专业意见，也不具备紧急救助能力。重要事项应向可靠来源和有资质真人核验；紧急危险请立即联系当地急救、公安、监护人或可信赖的真人。

三、账号与安全
注册通常需要合法的用户名、有效邮箱和密码。不得冒用他人、出售、出租、出借、共享、批量注册或用自动化工具绕过验证、配额和安全限制。请保护密码、邮箱、设备、令牌、重置链接和微信绑定；发现异常应立即修改密码、退出、解绑并联系运营者。因运营者安全缺陷或第三方侵害造成的责任，仍按法律和过错承担，不会一概转嫁给用户。

四、用户内容和授权
你保留对文字、图片、人格、记忆和参考图依法享有的权利，并应确保具有合法来源和充分授权。为提供服务，你仅授予运营者一项非独占、范围和期限限于履约所需的存储、传输、展示、检索、生成和编辑许可；该许可不包含擅自公开、出售、广告利用或未经另行合法依据训练基础模型。

提交他人姓名、肖像、人脸、声音、聊天记录、私密信息或作品前，应完成必要告知和授权。使用人脸、人声编辑功能时应取得被编辑者的单独同意。不得上传国家秘密、商业秘密、盗取数据、未授权私密影像或他人个人信息。

五、生成内容和标识
生成内容不保证具有独创性、唯一性、可获得知识产权保护或不涉及第三方权利。商业使用、广告、新闻、销售和高风险用途前，应自行核验并取得必要授权。

Miao 应在界面、文字/图片合理位置和/或文件元数据中添加法定 AI 生成合成标识。你对外发布时应主动声明 AI 生成并使用平台标识功能，不得通过裁剪、覆盖、清除元数据或其他手段恶意删除、篡改、伪造或隐匿标识。因运营者未履行法定标识义务造成的责任，不能转嫁给用户。

六、禁止行为
不得利用服务实施违法犯罪、恐怖极端、淫秽色情、儿童性剥削、暴力伤害、自杀自残诱导、诈骗赌博、虚假新闻、冒充官方或真人、网络暴力、人肉搜索、歧视骚扰、侵犯知识产权/肖像/名誉/隐私、非法处理个人信息、制作恶意程序、攻击渗透、盗取提示词或密钥、绕过安全和配额、批量抓取转售、删除 AI 标识或其他损害国家安全、公共利益和他人权益的行为。

人格不得未经授权冒充现实人物、未成年人、机关、媒体或专业机构；参考图和图片理解不得用于识别、跟踪、歧视、骚扰或深度伪造他人。所谓角色扮演、研究、虚构或测试目的不当然免除责任。

七、配额、变更和处置
服务可设置消息、图片、存储等配额。运营者可为成本、安全和合规合理调整免费资源并提前说明，不得无故减损已购买且未履行的权益。当前没有完整付费或自动续费流程；未来收费必须在付款前明确价格、有效期、退款和终止方式，不得默认勾选或强制搭售。

对违法违规或现实安全风险，运营者可按比例采取提示、拒绝生成、删除或断开内容、限制功能、暂停或终止账号、保存记录和依法报告等措施，并应在法律允许时说明理由、期限和申诉渠道。管理员不得无正当理由浏览私人聊天。

模型、网络、服务器、微信等可能维护或中断。运营者应尽合理努力恢复；永久停止、重大调整或强制升级时，应合理通知并提供数据和剩余权益安排。卸载应用不等于删除服务端数据，完整删除或注销按隐私政策处理。

八、责任边界
运营者不保证 AI 绝对准确或服务永不中断，但应按法律、明示承诺和合理质量要求提供服务并修复可控问题。本协议不排除或限制因人身损害、故意或重大过失财产损失、侵犯个人信息/人格/消费者权利及法律禁止免除的责任，也不设置不公平的单方免责、无限赔偿或排除司法救济条款。

九、未成年人
服务当前仅面向年满十八周岁用户。未满十八周岁者不得注册或使用；运营者应落实有效年龄门槛。若未来依法向部分未成年人开放，须先完成年龄识别、监护验证和同意、专门规则、未成年人模式及相应保护措施，且不得提供可能影响身心健康的模拟亲属、现实人物、虚拟伴侣或其他亲密关系服务。

十、更新、投诉和争议
运营者必须提供真实可达的客服、举报、权利投诉和未成年人渠道。与用户有重大利害关系的格式条款应显著提示并按要求说明；涉及实质增加责任、减少权利、收费、争议解决或个人信息的重要更新，应重新取得确认，不能仅以静默、默认勾选或继续使用推定接受。

发生争议先友好协商；协商不成，可依法向消费者组织、行政机关投诉举报，或向有管辖权的人民法院起诉。本协议不强制只能向运营者所在地法院起诉，不排除用户依法选择救济的权利。"""
