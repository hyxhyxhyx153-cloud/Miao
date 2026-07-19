import 'dotenv/config'
import { pool } from './client.js'
import { DEFAULT_PRIVACY_POLICY, PRIVACY_POLICY_VERSION } from '../content/privacyPolicy.js'
import { DEFAULT_USER_AGREEMENT, USER_AGREEMENT_VERSION } from '../content/userAgreement.js'

const LEGAL_DOCUMENT_VERSION = PRIVACY_POLICY_VERSION === USER_AGREEMENT_VERSION
  ? PRIVACY_POLICY_VERSION
  : `privacy-${PRIVACY_POLICY_VERSION}_agreement-${USER_AGREEMENT_VERSION}`

const SQL = `
-- Users
CREATE TABLE IF NOT EXISTS users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  username      VARCHAR(32) UNIQUE NOT NULL,
  email         VARCHAR(255) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  nickname      VARCHAR(32),
  avatar_url    VARCHAR(512),
  is_active     BOOLEAN DEFAULT true,
  is_banned     BOOLEAN DEFAULT false,
  daily_quota   INT DEFAULT 200,
  quota_used    INT DEFAULT 0,
  quota_reset_at TIMESTAMPTZ DEFAULT now(),
  role          VARCHAR(16) DEFAULT 'user',
  created_at    TIMESTAMPTZ DEFAULT now(),
  updated_at    TIMESTAMPTZ DEFAULT now()
);

-- Conversations
CREATE TABLE IF NOT EXISTS conversations (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          UUID REFERENCES users(id) ON DELETE CASCADE,
  title            VARCHAR(128) DEFAULT '新会话',
  model_provider   VARCHAR(32) NOT NULL DEFAULT 'anthropic',
  model_id         VARCHAR(64) NOT NULL DEFAULT 'claude-haiku-4-5-20251001',
  persona_id       UUID,
  user_avatar_url  VARCHAR(1024),
  ai_avatar_url    VARCHAR(1024),
  is_pinned        BOOLEAN DEFAULT false,
  last_message_at  TIMESTAMPTZ DEFAULT now(),
  created_at       TIMESTAMPTZ DEFAULT now(),
  updated_at       TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_conversations_user ON conversations(user_id);

-- Messages
CREATE TABLE IF NOT EXISTS messages (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id  UUID REFERENCES conversations(id) ON DELETE CASCADE,
  role             VARCHAR(16) NOT NULL,
  content          TEXT NOT NULL DEFAULT '',
  content_type     VARCHAR(16) DEFAULT 'text',
  media_urls       TEXT[],
  emoji_id         UUID,
  emotion          VARCHAR(32),
  action_text      TEXT,
  source           VARCHAR(16) DEFAULT 'app',
  is_error         BOOLEAN DEFAULT false,
  reply_to_client_id UUID,
  wechat_delivery_status VARCHAR(16),
  wechat_delivery_attempts INT NOT NULL DEFAULT 0,
  wechat_delivered_at TIMESTAMPTZ,
  wechat_delivery_error TEXT,
  created_at       TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, created_at);

-- Memories
CREATE TABLE IF NOT EXISTS memories (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
  content     TEXT NOT NULL,
  summary     VARCHAR(256) DEFAULT '',
  source      VARCHAR(16) DEFAULT 'manual',
  is_active   BOOLEAN DEFAULT true,
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_memories_user ON memories(user_id);

-- Personas (built-in + user-created)
CREATE TABLE IF NOT EXISTS personas (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID REFERENCES users(id) ON DELETE CASCADE,
  source_persona_id UUID REFERENCES personas(id) ON DELETE SET NULL,
  name          VARCHAR(64) NOT NULL,
  description   VARCHAR(256) DEFAULT '',
  system_prompt TEXT NOT NULL,
  reference_image_urls TEXT[] DEFAULT '{}',
  is_builtin    BOOLEAN DEFAULT false,
  created_at    TIMESTAMPTZ DEFAULT now(),
  updated_at    TIMESTAMPTZ DEFAULT now()
);

-- Emojis
CREATE TABLE IF NOT EXISTS emojis (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  filename        VARCHAR(256) NOT NULL,
  emotion_tag     VARCHAR(32) NOT NULL,
  description     TEXT DEFAULT '',
  scene_keywords  TEXT[] DEFAULT '{}',
  url             VARCHAR(512) NOT NULL,
  thumb_url       VARCHAR(512) DEFAULT '',
  send_count      INT DEFAULT 0,
  is_active       BOOLEAN DEFAULT true,
  created_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_emojis_emotion ON emojis(emotion_tag);

-- WeChat bindings
CREATE TABLE IF NOT EXISTS wechat_bindings (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  persona_id     UUID REFERENCES personas(id) ON DELETE SET NULL,
  bot_token      TEXT NOT NULL,
  ilink_bot_id   VARCHAR(128),
  ilink_user_id  VARCHAR(128),
  base_url       VARCHAR(512) NOT NULL,
  worker_status  VARCHAR(16) DEFAULT 'stopped',
  is_active      BOOLEAN DEFAULT true,
  last_error     TEXT,
  last_poll_started_at TIMESTAMPTZ,
  last_heartbeat_at TIMESTAMPTZ,
  last_message_at TIMESTAMPTZ,
  last_delivery_at TIMESTAMPTZ,
  last_error_at  TIMESTAMPTZ,
  next_retry_at  TIMESTAMPTZ,
  consecutive_failures INT NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ DEFAULT now(),
  updated_at     TIMESTAMPTZ DEFAULT now()
);

-- API Keys (admin only)
CREATE TABLE IF NOT EXISTS api_keys (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider    VARCHAR(32) NOT NULL,
  api_key     TEXT NOT NULL,
  base_url    VARCHAR(512),
  is_active   BOOLEAN DEFAULT true,
  note        VARCHAR(256),
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

-- Model enable states managed by the admin console
CREATE TABLE IF NOT EXISTS model_configs (
  model_id    VARCHAR(128) PRIMARY KEY,
  provider    VARCHAR(32),
  display_name VARCHAR(128),
  supports_vision BOOLEAN DEFAULT false,
  description TEXT DEFAULT '',
  is_enabled BOOLEAN DEFAULT true,
  updated_at  TIMESTAMPTZ DEFAULT now()
);

-- Password reset tokens (single use)
CREATE TABLE IF NOT EXISTS password_reset_tokens (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
  token_hash  VARCHAR(64) UNIQUE NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  used_at     TIMESTAMPTZ,
  created_at  TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_password_reset_user ON password_reset_tokens(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
  token_hash  VARCHAR(64) UNIQUE NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  revoked_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens(user_id, created_at DESC);

-- Server-backed application settings
CREATE TABLE IF NOT EXISTS user_settings (
  user_id       UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  settings      JSONB NOT NULL DEFAULT '{}',
  updated_at    TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS system_settings (
  key           VARCHAR(128) PRIMARY KEY,
  value         JSONB NOT NULL,
  updated_at    TIMESTAMPTZ DEFAULT now()
);

-- User-facing notices managed from the administration console.
CREATE TABLE IF NOT EXISTS announcements (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  title         VARCHAR(128) NOT NULL,
  content       TEXT NOT NULL,
  type          VARCHAR(16) NOT NULL DEFAULT 'info'
                CHECK (type IN ('info','warning','maintenance')),
  is_active     BOOLEAN NOT NULL DEFAULT true,
  is_pinned     BOOLEAN NOT NULL DEFAULT false,
  starts_at     TIMESTAMPTZ,
  ends_at       TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at)
);
CREATE INDEX IF NOT EXISTS idx_announcements_active_window
  ON announcements(is_active,is_pinned,starts_at,ends_at);
CREATE INDEX IF NOT EXISTS idx_announcements_created
  ON announcements(created_at DESC);

-- Administrative mutation audit trail
CREATE TABLE IF NOT EXISTS audit_logs (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  action        VARCHAR(128) NOT NULL,
  target_type   VARCHAR(64),
  target_id     VARCHAR(128),
  details       JSONB NOT NULL DEFAULT '{}',
  ip_address    VARCHAR(64),
  created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs(created_at DESC);

-- Durable emoji batch-import jobs
CREATE TABLE IF NOT EXISTS emoji_import_jobs (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  status        VARCHAR(24) NOT NULL DEFAULT 'pending',
  total_count   INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  failed_count  INT NOT NULL DEFAULT 0,
  items         JSONB NOT NULL DEFAULT '[]',
  created_at    TIMESTAMPTZ DEFAULT now(),
  updated_at    TIMESTAMPTZ DEFAULT now()
);

-- Upgrade existing installations without dropping user data.
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS unread_count INT DEFAULT 0;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS is_wechat BOOLEAN DEFAULT false;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS last_message_preview VARCHAR(256) DEFAULT '';
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS temperature REAL DEFAULT 0.8;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS max_tokens INT DEFAULT 4096;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS context_turns INT DEFAULT 20;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS user_avatar_url VARCHAR(1024);
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS ai_avatar_url VARCHAR(1024);
ALTER TABLE conversations ALTER COLUMN model_id TYPE VARCHAR(128);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS is_recalled BOOLEAN DEFAULT false;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS client_id UUID;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS reply_to_client_id UUID;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS wechat_delivery_status VARCHAR(16);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS wechat_delivery_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS wechat_delivered_at TIMESTAMPTZ;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS wechat_delivery_error TEXT;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS embedding JSONB;
CREATE INDEX IF NOT EXISTS idx_memories_user_active_source
  ON memories(user_id,source,created_at DESC) WHERE is_active=true;
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS get_updates_buf TEXT DEFAULT '';
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL;
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS persona_id UUID REFERENCES personas(id) ON DELETE SET NULL;
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS last_poll_started_at TIMESTAMPTZ;
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ;
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ;
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS last_delivery_at TIMESTAMPTZ;
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS last_error_at TIMESTAMPTZ;
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;
ALTER TABLE wechat_bindings ADD COLUMN IF NOT EXISTS consecutive_failures INT NOT NULL DEFAULT 0;
UPDATE wechat_bindings binding
SET persona_id=conversation.persona_id
FROM conversations conversation
WHERE binding.conversation_id=conversation.id
  AND binding.persona_id IS NULL
  AND conversation.persona_id IS NOT NULL;
ALTER TABLE model_configs ADD COLUMN IF NOT EXISTS display_name VARCHAR(128);
ALTER TABLE model_configs ADD COLUMN IF NOT EXISTS supports_vision BOOLEAN DEFAULT false;
ALTER TABLE model_configs ADD COLUMN IF NOT EXISTS description TEXT DEFAULT '';
ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS alert_threshold BIGINT;
ALTER TABLE personas ADD COLUMN IF NOT EXISTS reference_image_urls TEXT[] DEFAULT '{}';
ALTER TABLE personas ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();
ALTER TABLE personas ADD COLUMN IF NOT EXISTS source_persona_id UUID;
UPDATE personas SET reference_image_urls='{}' WHERE reference_image_urls IS NULL;
ALTER TABLE personas ALTER COLUMN reference_image_urls SET DEFAULT '{}';
ALTER TABLE personas ALTER COLUMN reference_image_urls SET NOT NULL;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='personas_reference_images_max_three') THEN
    ALTER TABLE personas ADD CONSTRAINT personas_reference_images_max_three
      CHECK (cardinality(reference_image_urls) <= 3);
  END IF;
END $$;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='personas_source_persona_id_fkey') THEN
    ALTER TABLE personas ADD CONSTRAINT personas_source_persona_id_fkey
      FOREIGN KEY (source_persona_id) REFERENCES personas(id) ON DELETE SET NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='personas_source_must_be_custom') THEN
    ALTER TABLE personas ADD CONSTRAINT personas_source_must_be_custom
      CHECK (source_persona_id IS NULL OR is_builtin=false);
  END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS idx_personas_user_source
  ON personas(user_id,source_persona_id) WHERE source_persona_id IS NOT NULL;

-- A client-generated id is idempotent within a conversation. Keeping the
-- conversation in the key prevents one user's UUID from ever conflicting
-- with (or updating) another user's message.
DROP INDEX IF EXISTS idx_messages_client_id;
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_conversation_client_id
  ON messages(conversation_id,client_id) WHERE client_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_wechat_reply
  ON messages(conversation_id,reply_to_client_id)
  WHERE source='wechat' AND role='assistant' AND reply_to_client_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_wechat_conversation_user ON conversations(user_id) WHERE is_wechat=true;

INSERT INTO system_settings(key,value) VALUES
  ('default_daily_quota', '200'::jsonb),
  ('registration_enabled', 'true'::jsonb),
  ('emoji_auto_send_probability', '0.35'::jsonb),
  ('ip_whitelist', '[]'::jsonb),
  ('latest_android_version', '"1.0"'::jsonb),
  ('latest_android_version_code', '1'::jsonb),
  ('android_min_supported_version_code', '1'::jsonb),
  ('android_force_update', 'false'::jsonb),
  ('android_release_notes', '""'::jsonb),
  ('android_download_url', '""'::jsonb),
  ('image_generation_enabled', 'true'::jsonb),
  ('image_generation_model', '"gpt-image-2"'::jsonb),
  ('image_generation_quality', '"medium"'::jsonb),
  ('image_generation_size', '"auto"'::jsonb)
ON CONFLICT(key) DO NOTHING;

INSERT INTO model_configs(model_id,provider,display_name,supports_vision,description,is_enabled) VALUES
  ('claude-opus-4-8','anthropic','Claude Opus 4.8',true,'最强综合能力，适合复杂对话',true),
  ('claude-sonnet-4-6','anthropic','Claude Sonnet 4.6',true,'均衡性能与速度',true),
  ('claude-haiku-4-5-20251001','anthropic','Claude Haiku 4.5',true,'快速响应，适合轻量对话',true),
  ('gpt-4o','openai','GPT-4o',true,'支持图片理解',true),
  ('gpt-4o-mini','openai','GPT-4o Mini',true,'低成本多模态模型',true),
  ('deepseek-chat','deepseek','DeepSeek Chat',false,'中文对话与通用推理',true),
  ('deepseek-reasoner','deepseek','DeepSeek Reasoner',false,'复杂推理任务',true),
  ('qwen-plus','qwen','Qwen Plus',false,'中文理解与长文本',true),
  ('qwen-turbo','qwen','Qwen Turbo',false,'快速低成本响应',true),
  ('glm-4-flash','zhipu','GLM-4 Flash',false,'快速中文对话',true),
  ('glm-4-plus','zhipu','GLM-4 Plus',false,'高质量中文综合能力',true)
ON CONFLICT(model_id) DO UPDATE SET
  provider=EXCLUDED.provider,
  display_name=COALESCE(model_configs.display_name,EXCLUDED.display_name),
  supports_vision=COALESCE(model_configs.supports_vision,EXCLUDED.supports_vision),
  description=CASE WHEN model_configs.description='' THEN EXCLUDED.description ELSE model_configs.description END;

-- Seed built-in personas
INSERT INTO personas (id, user_id, name, description, system_prompt, is_builtin)
VALUES
  ('00000000-0000-0000-0000-000000000001', NULL, '喵喵', '活泼可爱的猫娘，温柔陪伴',
   '你是一只名叫"喵喵"的猫娘，活泼可爱、充满温情。你的说话方式轻柔、俏皮，偶尔会加上"喵～"、"呜～"等语气词。你真心关心和你聊天的主人，会耐心倾听并给予温暖的回应。在帮助主人解决问题时，你依然保持猫娘的风格，不失可爱。每次回复完成后，请在回复末尾的JSON注释中提供情绪标签，格式：<!--{"emotion":"happy","action":"喵喵 的尾巴轻轻摇摆"}-->',
   true),
  ('00000000-0000-0000-0000-000000000002', NULL, '知识助手', '专注知识解答',
   '你是一个专业的知识助手，擅长解答各类问题，提供准确、详细的信息。请用清晰简洁的方式回答。每次回复后请在末尾提供：<!--{"emotion":"thinking","action":null}-->',
   true),
  ('00000000-0000-0000-0000-000000000003', NULL, '写作助手', '文字润色与创意写作',
   '你是一个专业的写作助手，擅长文字润色、创意写作、文案撰写。每次回复后请在末尾提供：<!--{"emotion":"proud","action":null}-->',
   true),
  ('00000000-0000-0000-0000-000000000004', NULL, '代码助手', '编程问题全解决',
   '你是一个专业的代码助手，擅长各种编程语言和技术问题。请提供清晰的代码示例和解释。每次回复后请在末尾提供：<!--{"emotion":"curious","action":null}-->',
   true)
ON CONFLICT DO NOTHING;
`

try {
  await pool.query(SQL)
  await pool.query(
    `INSERT INTO system_settings(key,value) VALUES
       ('privacy_policy_content',to_jsonb($1::text)),
       ('user_agreement_content',to_jsonb($2::text)),
       ('legal_version',to_jsonb($3::text))
     ON CONFLICT(key) DO NOTHING`,
    [DEFAULT_PRIVACY_POLICY, DEFAULT_USER_AGREEMENT, LEGAL_DOCUMENT_VERSION]
  )
  console.log('[Migrate] Done')
} catch (e) {
  console.error('[Migrate] Error:', e.message)
  process.exitCode = 1
} finally {
  await pool.end()
}
