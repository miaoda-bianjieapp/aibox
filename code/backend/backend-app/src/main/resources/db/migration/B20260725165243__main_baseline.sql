-- Baseline for new databases through V20260725165243.
-- Existing Flyway histories ignore this file; do not edit merged V migrations.
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.14
-- Dumped by pg_dump version 16.14

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: artifact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.artifact (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    task_id uuid NOT NULL,
    run_id uuid NOT NULL,
    parent_artifact_id uuid,
    version_number integer NOT NULL,
    kind character varying(80) NOT NULL,
    title character varying(240) NOT NULL,
    mime_type character varying(160) NOT NULL,
    content_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    metadata_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: artifact_asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.artifact_asset (
    artifact_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    role character varying(80) NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    original_name character varying(500) NOT NULL,
    media_type character varying(200) NOT NULL,
    size_bytes bigint NOT NULL,
    storage_key character varying(500) NOT NULL,
    sha256 character varying(64) NOT NULL,
    status character varying(30) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    origin character varying(30) DEFAULT 'USER_UPLOAD'::character varying NOT NULL,
    media_category character varying(30) DEFAULT 'OTHER'::character varying NOT NULL,
    blob_id uuid NOT NULL,
    CONSTRAINT ck_asset_media_category CHECK (((media_category)::text = ANY ((ARRAY['IMAGE'::character varying, 'VIDEO'::character varying, 'AUDIO'::character varying, 'DOCUMENT'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT ck_asset_origin CHECK (((origin)::text = ANY ((ARRAY['USER_UPLOAD'::character varying, 'MODEL_OUTPUT'::character varying, 'APP_DERIVED'::character varying])::text[]))),
    CONSTRAINT ck_asset_status CHECK (((status)::text = ANY ((ARRAY['READY'::character varying, 'TEMPORARY'::character varying, 'DELETED'::character varying])::text[])))
);


--
-- Name: asset_blob; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_blob (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    sha256 character varying(64) NOT NULL,
    size_bytes bigint NOT NULL,
    storage_key character varying(500) NOT NULL,
    storage_backend character varying(30) DEFAULT 'LOCAL_FS'::character varying NOT NULL,
    status character varying(30) DEFAULT 'READY'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT ck_asset_blob_backend CHECK (((storage_backend)::text = 'LOCAL_FS'::text)),
    CONSTRAINT ck_asset_blob_status CHECK (((status)::text = ANY ((ARRAY['READY'::character varying, 'DELETED'::character varying])::text[])))
);


--
-- Name: document_chunk; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_chunk (
    id uuid NOT NULL,
    document_index_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    ordinal integer NOT NULL,
    text_content text NOT NULL,
    locator_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    search_metadata_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: document_index; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_index (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    vision_deployment_code character varying(120) NOT NULL,
    parser_version integer NOT NULL,
    status character varying(30) NOT NULL,
    content_hash character varying(64) NOT NULL,
    statistics_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    error_code character varying(100),
    error_message character varying(1000),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_document_index_status CHECK (((status)::text = ANY ((ARRAY['PROCESSING'::character varying, 'READY'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: feature_definition; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feature_definition (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    code character varying(120) NOT NULL,
    display_name character varying(120) NOT NULL,
    description character varying(500) NOT NULL,
    status character varying(30) NOT NULL,
    current_version integer NOT NULL,
    result_type character varying(80) NOT NULL,
    renderer_key character varying(80) NOT NULL,
    execution_mode character varying(30) NOT NULL,
    sort_order integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_feature_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'INTERNAL'::character varying, 'BETA'::character varying, 'PUBLISHED'::character varying, 'DISABLED'::character varying])::text[])))
);


--
-- Name: feature_model_option; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feature_model_option (
    policy_id uuid NOT NULL,
    deployment_code character varying(120) NOT NULL,
    display_name character varying(120) NOT NULL,
    description character varying(500) DEFAULT ''::character varying NOT NULL,
    sort_order integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL
);


--
-- Name: feature_model_policy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feature_model_policy (
    id uuid NOT NULL,
    feature_code character varying(120) NOT NULL,
    capability character varying(80) NOT NULL,
    default_deployment_code character varying(120) NOT NULL,
    allow_user_selection boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: feature_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feature_version (
    id uuid NOT NULL,
    feature_id uuid NOT NULL,
    version integer NOT NULL,
    input_schema_json jsonb NOT NULL,
    ui_schema_json jsonb NOT NULL,
    output_schema_json jsonb NOT NULL,
    config_json jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: idempotency_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.idempotency_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    scope character varying(200) NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character(64) NOT NULL,
    resource_type character varying(80) NOT NULL,
    resource_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.job (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    type character varying(80) NOT NULL,
    status character varying(30) NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 3 NOT NULL,
    available_at timestamp with time zone NOT NULL,
    locked_by character varying(160),
    locked_until timestamp with time zone,
    last_error character varying(2000),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_job_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: model_deployment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_deployment (
    id uuid NOT NULL,
    code character varying(120) NOT NULL,
    provider_code character varying(80) NOT NULL,
    display_name character varying(120) NOT NULL,
    description character varying(500) DEFAULT ''::character varying NOT NULL,
    capability character varying(80) NOT NULL,
    provider_model character varying(160) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    selectable boolean DEFAULT false NOT NULL,
    config_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: model_provider; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_provider (
    id uuid NOT NULL,
    code character varying(80) NOT NULL,
    display_name character varying(120) NOT NULL,
    protocol character varying(80) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    provider_kind character varying(20) DEFAULT 'OFFICIAL'::character varying NOT NULL,
    CONSTRAINT ck_model_provider_kind CHECK (((provider_kind)::text = ANY ((ARRAY['OFFICIAL'::character varying, 'RELAY'::character varying])::text[])))
);


--
-- Name: model_route; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_route (
    id uuid NOT NULL,
    model_alias character varying(120) NOT NULL,
    capability character varying(80) NOT NULL,
    deployment_code character varying(120) NOT NULL,
    priority integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: outbox_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outbox_event (
    id uuid NOT NULL,
    aggregate_type character varying(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type character varying(120) NOT NULL,
    payload_json jsonb NOT NULL,
    status character varying(30) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    published_at timestamp with time zone
);


--
-- Name: project; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    description character varying(1000) DEFAULT ''::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: provider_invocation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_invocation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid,
    capability character varying(80) NOT NULL,
    provider_code character varying(80) NOT NULL,
    model_alias character varying(120) NOT NULL,
    provider_model character varying(160),
    provider_request_id character varying(240),
    status character varying(30) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    input_units bigint,
    output_units bigint,
    error_code character varying(100),
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    deployment_code character varying(120),
    invocation_scope character varying(30) DEFAULT 'TASK_RUN'::character varying NOT NULL,
    CONSTRAINT ck_provider_invocation_scope CHECK (((((invocation_scope)::text = 'TASK_RUN'::text) AND (run_id IS NOT NULL)) OR (((invocation_scope)::text = 'PROMPT_ASSIST'::text) AND (run_id IS NULL))))
);


--
-- Name: run_output_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.run_output_event (
    id bigint NOT NULL,
    run_id uuid NOT NULL,
    channel character varying(80) NOT NULL,
    sequence bigint NOT NULL,
    event_type character varying(40) NOT NULL,
    payload_json jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: run_output_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.run_output_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: run_output_event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.run_output_event_id_seq OWNED BY public.run_output_event.id;


--
-- Name: run_output_stream; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.run_output_stream (
    run_id uuid NOT NULL,
    channel character varying(80) NOT NULL,
    format character varying(40) NOT NULL,
    content_text text DEFAULT ''::text NOT NULL,
    status character varying(30) NOT NULL,
    last_sequence bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_run_output_stream_status CHECK (((status)::text = ANY ((ARRAY['STREAMING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'PARTIAL'::character varying])::text[])))
);


--
-- Name: task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    project_id uuid,
    feature_code character varying(120) NOT NULL,
    title character varying(240) NOT NULL,
    status character varying(30) NOT NULL,
    current_artifact_id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT ck_task_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'COMPLETED'::character varying, 'ARCHIVED'::character varying])::text[])))
);


--
-- Name: task_asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_asset (
    task_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    role character varying(80) NOT NULL,
    status character varying(30) NOT NULL,
    ordinal integer NOT NULL,
    snapshot_name character varying(500) NOT NULL,
    snapshot_media_type character varying(200) NOT NULL,
    snapshot_size_bytes bigint NOT NULL,
    added_at timestamp with time zone NOT NULL,
    removed_at timestamp with time zone,
    CONSTRAINT ck_task_asset_ordinal CHECK ((ordinal >= 0)),
    CONSTRAINT ck_task_asset_size CHECK ((snapshot_size_bytes >= 0)),
    CONSTRAINT ck_task_asset_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REMOVED'::character varying])::text[])))
);


--
-- Name: task_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    task_id uuid NOT NULL,
    run_number integer NOT NULL,
    feature_code character varying(120) NOT NULL,
    feature_version integer NOT NULL,
    status character varying(30) NOT NULL,
    parameters_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    input_asset_ids_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    cancel_requested boolean DEFAULT false NOT NULL,
    error_code character varying(100),
    error_message character varying(2000),
    queued_at timestamp with time zone,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    base_artifact_id uuid,
    selected_model_code character varying(120),
    selected_models_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT ck_run_status CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'VALIDATING'::character varying, 'QUEUED'::character varying, 'RUNNING'::character varying, 'WAITING_CALLBACK'::character varying, 'SUCCEEDED'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: task_run_asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_run_asset (
    run_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    direction character varying(20) NOT NULL,
    field_key character varying(120) NOT NULL,
    ordinal integer NOT NULL,
    snapshot_name character varying(500) NOT NULL,
    snapshot_media_type character varying(200) NOT NULL,
    snapshot_size_bytes bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_task_run_asset_direction CHECK (((direction)::text = 'INPUT'::text))
);


--
-- Name: workspace; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workspace (
    id uuid NOT NULL,
    code character varying(80) NOT NULL,
    display_name character varying(120) NOT NULL,
    description character varying(500) NOT NULL,
    icon_key character varying(80) NOT NULL,
    groups_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    search_terms_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    sort_order integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: run_output_event id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_output_event ALTER COLUMN id SET DEFAULT nextval('public.run_output_event_id_seq'::regclass);


--
-- Data for Name: artifact; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: artifact_asset; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: asset; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: asset_blob; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_chunk; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_index; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: feature_definition; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('a2d3a2fc-9b0f-4d2a-8d78-31b706f4eb2f', '10000000-0000-0000-0000-000000000001', 'writing.translate', '文本翻译', '自动识别源语言，并将纯文本忠实翻译为指定目标语言。', 'INTERNAL', 1, 'rich_text', 'rich_text_editor', 'ASYNC', 30, '2026-07-27 16:52:54.30246+08', '2026-07-27 16:52:54.30246+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('2dfe60b8-f04f-469e-b4a2-adb446a44b58', '10000000-0000-0000-0000-000000000003', 'image.enhance', '清晰修复', '对单张图片执行放大、去模糊、降噪或老照片修复。', 'INTERNAL', 2, 'image', 'image', 'ASYNC', 30, '2026-07-27 16:52:54.352787+08', '2026-07-27 16:52:54.358224+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('af9b8384-1ca6-4cc8-888d-a71b6965ea50', '10000000-0000-0000-0000-000000000003', 'image.expand', '扩图与改比例', '可选择改变画布比例，或保持原图比例按倍数向四周扩展。', 'INTERNAL', 5, 'image', 'image', 'ASYNC', 20, '2026-07-27 16:52:54.364304+08', '2026-07-27 16:52:54.400742+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'writing.draft', '从零起草', '根据主题、受众和语气生成结构化初稿。', 'INTERNAL', 2, 'rich_text', 'rich_text_editor', 'ASYNC', 10, '2026-07-27 16:52:54.099889+08', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('85435eee-bf94-4bed-a7d5-5d349c9bbba1', '10000000-0000-0000-0000-000000000001', 'writing.rewrite_polish', '改写与润色', '在保持事实、核心含义和原文语言的前提下改写或润色文本。', 'INTERNAL', 3, 'rich_text', 'rich_text_editor', 'ASYNC', 20, '2026-07-27 16:52:54.287156+08', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('3e43c7cb-6f6a-4d02-a415-c28e2a0e2c93', '10000000-0000-0000-0000-000000000001', 'writing.outline_ideas', '大纲与思路', '根据文章标题、主旨和风格生成可编辑的纯文本写作框架。', 'INTERNAL', 2, 'outline_text', 'outline_text_editor', 'ASYNC', 40, '2026-07-27 16:52:54.314875+08', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('d72718f0-b5d2-4a2d-8e02-2af0ec5f4e2a', '10000000-0000-0000-0000-000000000003', 'image.background_edit', '抠图与换背景', '自动识别图片主体，移除背景或根据文字和参考图更换背景。', 'INTERNAL', 4, 'image', 'image', 'ASYNC', 20, '2026-07-27 16:52:54.332534+08', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('3ecffe9d-176d-462a-9a62-f14969905676', '10000000-0000-0000-0000-000000000003', 'image.local_edit', '图片局部编辑', '涂抹需要修改的图片区域，并通过文字指令只重绘选区内容。', 'INTERNAL', 3, 'image', 'image', 'ASYNC', 40, '2026-07-27 16:52:54.407511+08', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('20000000-0000-0000-0000-000000000100', '10000000-0000-0000-0000-000000000003', 'image.generate', 'AI 生图', '根据文字描述和可选参考图片生成一张新图片。', 'INTERNAL', 4, 'image', 'image', 'ASYNC', 10, '2026-07-27 16:52:54.27643+08', '2026-07-27 16:52:54.573089+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('20000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000006', 'document.qa', '文档问答', '基于当前会话中的文档进行带精确来源的多轮问答。', 'INTERNAL', 2, 'document_chat', 'document_qa_chat', 'ASYNC', 10, '2026-07-27 16:52:54.583467+08', '2026-07-27 16:52:54.650016+08');
INSERT INTO public.feature_definition (id, workspace_id, code, display_name, description, status, current_version, result_type, renderer_key, execution_mode, sort_order, created_at, updated_at) VALUES ('25d807ac-1958-477c-b60e-2186ef946f5e', '10000000-0000-0000-0000-000000000006', 'document.summary', '文档总结', '总结单个 PDF、Office、Markdown、TXT、JSON 或 CSV 文档，输出摘要、章节要点、结论和行动项。', 'INTERNAL', 3, 'rich_text', 'rich_text_editor', 'ASYNC', 10, '2026-07-27 16:52:54.635302+08', '2026-07-27 16:52:54.655239+08');


--
-- Data for Name: feature_model_option; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000100', 'codex2api-gpt-image-2-image', 'GPT Image 2', '适合高质量图片生成与多参考图修改，通过 Codex2API 中转调用。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000001', 'codex2api-gpt-5-4-mini-text', 'GPT-5.4 Mini', '轻量均衡的文本生成模型，通过 Codex2API 中转服务调用。', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000001', 'codex2api-gpt-5-6-text', 'GPT-5.6', '高质量文本生成模型，通过 Codex2API 中转服务调用。', 30, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('ef5d2b24-a813-4952-b487-9aea754fa045', 'codex2api-gpt-5-4-mini-text', 'GPT-5.4 Mini', '轻量均衡的文本生成模型，通过 Codex2API 中转服务调用。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('ef5d2b24-a813-4952-b487-9aea754fa045', 'codex2api-gpt-5-6-text', 'GPT-5.6', '高质量文本生成模型，通过 Codex2API 中转服务调用。', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('5a2a44fe-cc64-4ebd-ae1f-d38ccb4518c9', 'codex2api-gpt-5-4-mini-text', 'GPT-5.4 Mini', '轻量均衡的文本生成模型，通过 Codex2API 中转服务调用。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('5a2a44fe-cc64-4ebd-ae1f-d38ccb4518c9', 'codex2api-gpt-5-6-text', 'GPT-5.6', '高质量文本生成模型，通过 Codex2API 中转服务调用。', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('b81a198e-449f-416f-b6ec-6449645986e7', 'codex2api-gpt-5-4-mini-text', 'GPT-5.4 Mini', '轻量均衡的文本生成模型，通过 Codex2API 中转服务调用。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('b81a198e-449f-416f-b6ec-6449645986e7', 'codex2api-gpt-5-6-text', 'GPT-5.6', '高质量文本生成模型，通过 Codex2API 中转服务调用。', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('70daea0d-a317-4168-8d9c-235db21e429c', 'codex2api-gpt-image-2-image', 'GPT Image 2', '默认图片编辑模型，支持主体图和可选背景参考图。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('70daea0d-a317-4168-8d9c-235db21e429c', 'aliyun-qwen-image-2-0', 'Qwen Image 2.0', '中文图片处理模型；需管理员完成阿里云接口配置后使用。', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('07f8984a-6031-4594-a277-6c017923670a', 'codex2api-gpt-image-2-image', 'GPT Image 2', '默认模型，适合高质量图片编辑和生成式细节补全。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('07f8984a-6031-4594-a277-6c017923670a', 'aliyun-qwen-image-2-0', 'Qwen Image 2.0', '适合中文图片修复指令；需管理员完成阿里云接口配置后使用。', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('62aafc57-9d39-4bc0-b50f-126e09b58aae', 'codex2api-gpt-image-2-image', 'GPT Image 2', '支持居中扩图、遮罩编辑和严格保留原图区域，通过 Codex2API 中转调用。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('62aafc57-9d39-4bc0-b50f-126e09b58aae', 'aliyun-qwen-image-2-0', 'Qwen Image 2.0', '适合自然扩图和中文场景，通过阿里云百炼官方图像编辑接口调用。', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('58c6d3db-55cc-46d1-a7aa-929736e25478', 'codex2api-gpt-image-2-image', 'GPT Image 2 局部编辑', '支持通过独立蒙版限定重绘区域，并保留未涂抹区域。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000001', 'zhipu-glm-5v-turbo-text', 'GLM-5V Turbo', 'Default model for complete first drafts', 10, false);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('ef5d2b24-a813-4952-b487-9aea754fa045', 'zhipu-glm-5v-turbo-text', 'GLM-5V Turbo', 'General-purpose drafting and rewriting model', 30, false);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('5a2a44fe-cc64-4ebd-ae1f-d38ccb4518c9', 'zhipu-glm-5v-turbo-text', 'GLM-5V Turbo', '通用多语言文本处理模型。', 30, false);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('b81a198e-449f-416f-b6ec-6449645986e7', 'zhipu-glm-5v-turbo-text', 'GLM-5V Turbo', '通用中文和多语言构思模型。', 30, false);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000001', 'zhipu-glm-5-2-text', 'GLM-5.2', 'Official flagship model with a 200K context window for complex writing tasks', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000001', 'zhipu-glm-4-5-air-text', 'GLM-4.5-Air', 'Official lightweight model for faster and more cost-sensitive writing tasks', 15, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('ef5d2b24-a813-4952-b487-9aea754fa045', 'zhipu-glm-5-2-text', 'GLM-5.2', 'Official flagship model with a 200K context window for complex writing tasks', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('ef5d2b24-a813-4952-b487-9aea754fa045', 'zhipu-glm-4-5-air-text', 'GLM-4.5-Air', 'Official lightweight model for faster and more cost-sensitive writing tasks', 15, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('5a2a44fe-cc64-4ebd-ae1f-d38ccb4518c9', 'zhipu-glm-5-2-text', 'GLM-5.2', 'Official flagship model with a 200K context window for complex writing tasks', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('5a2a44fe-cc64-4ebd-ae1f-d38ccb4518c9', 'zhipu-glm-4-5-air-text', 'GLM-4.5-Air', 'Official lightweight model for faster and more cost-sensitive writing tasks', 15, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('b81a198e-449f-416f-b6ec-6449645986e7', 'zhipu-glm-5-2-text', 'GLM-5.2', 'Official flagship model with a 200K context window for complex writing tasks', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('b81a198e-449f-416f-b6ec-6449645986e7', 'zhipu-glm-4-5-air-text', 'GLM-4.5-Air', 'Official lightweight model for faster and more cost-sensitive writing tasks', 15, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000100', 'aliyun-qwen-image-2-0', 'Qwen Image 2.0', '适合中文文字和通用图片生成，仅支持文生图，不接收参考图片。', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000021', 'codex2api-gpt-5-6-sol-text', 'GPT-5.6 Sol', '质量优先的文档检索重排与回答模型', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000021', 'codex2api-gpt-5-4-mini-text', 'GPT-5.4 Mini', '速度与成本优先的文档问答模型', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000022', 'codex2api-gpt-5-6-sol-vision', 'GPT-5.6 Sol', '质量优先的扫描页和图表理解模型', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('43000000-0000-0000-0000-000000000022', 'codex2api-gpt-5-4-mini-vision', 'GPT-5.4 Mini', '速度与成本优先的扫描页和图表理解模型', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('b2221005-0e3b-4a80-a52d-093601bc2cfd', 'codex2api-gpt-5-6-sol-text', 'GPT-5.6 Sol', '默认模型，适合长文档和复杂结构总结。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('b2221005-0e3b-4a80-a52d-093601bc2cfd', 'codex2api-gpt-5-4-mini-text', 'GPT-5.4 Mini', '响应更轻量，适合结构清晰的常规文档。', 20, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('17290a44-6cb6-4a76-a5f6-86ee430fce2b', 'codex2api-gpt-5-6-sol-vision', 'GPT-5.6 Sol', '扫描页识别与图表理解使用同一 GPT-5.6 Sol 模型。', 10, true);
INSERT INTO public.feature_model_option (policy_id, deployment_code, display_name, description, sort_order, enabled) VALUES ('17290a44-6cb6-4a76-a5f6-86ee430fce2b', 'codex2api-gpt-5-4-mini-vision', 'GPT-5.4 Mini', '扫描页识别与图表理解使用同一 GPT-5.4 Mini 模型。', 20, true);


--
-- Data for Name: feature_model_policy; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('43000000-0000-0000-0000-000000000100', 'image.generate', 'IMAGE_GENERATION', 'codex2api-gpt-image-2-image', true, '2026-07-27 16:52:54.27643+08', '2026-07-27 16:52:54.27643+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('70daea0d-a317-4168-8d9c-235db21e429c', 'image.background_edit', 'IMAGE_GENERATION', 'codex2api-gpt-image-2-image', true, '2026-07-27 16:52:54.332534+08', '2026-07-27 16:52:54.332534+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('07f8984a-6031-4594-a277-6c017923670a', 'image.enhance', 'IMAGE_GENERATION', 'codex2api-gpt-image-2-image', true, '2026-07-27 16:52:54.352787+08', '2026-07-27 16:52:54.352787+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('62aafc57-9d39-4bc0-b50f-126e09b58aae', 'image.expand', 'IMAGE_GENERATION', 'codex2api-gpt-image-2-image', true, '2026-07-27 16:52:54.364304+08', '2026-07-27 16:52:54.371464+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('58c6d3db-55cc-46d1-a7aa-929736e25478', 'image.local_edit', 'IMAGE_GENERATION', 'codex2api-gpt-image-2-image', true, '2026-07-27 16:52:54.407511+08', '2026-07-27 16:52:54.416537+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('43000000-0000-0000-0000-000000000001', 'writing.draft', 'TEXT_GENERATION', 'zhipu-glm-5-2-text', true, '2026-07-27 16:52:54.183971+08', '2026-07-27 16:52:54.453248+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('ef5d2b24-a813-4952-b487-9aea754fa045', 'writing.rewrite_polish', 'TEXT_GENERATION', 'zhipu-glm-5-2-text', true, '2026-07-27 16:52:54.287156+08', '2026-07-27 16:52:54.453248+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('5a2a44fe-cc64-4ebd-ae1f-d38ccb4518c9', 'writing.translate', 'TEXT_GENERATION', 'zhipu-glm-5-2-text', true, '2026-07-27 16:52:54.30246+08', '2026-07-27 16:52:54.453248+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('b81a198e-449f-416f-b6ec-6449645986e7', 'writing.outline_ideas', 'TEXT_GENERATION', 'zhipu-glm-5-2-text', true, '2026-07-27 16:52:54.314875+08', '2026-07-27 16:52:54.453248+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('43000000-0000-0000-0000-000000000021', 'document.qa', 'TEXT_GENERATION', 'codex2api-gpt-5-6-sol-text', true, '2026-07-27 16:52:54.583467+08', '2026-07-27 16:52:54.583467+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('43000000-0000-0000-0000-000000000022', 'document.qa', 'VISION', 'codex2api-gpt-5-6-sol-vision', true, '2026-07-27 16:52:54.583467+08', '2026-07-27 16:52:54.583467+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('b2221005-0e3b-4a80-a52d-093601bc2cfd', 'document.summary', 'TEXT_GENERATION', 'codex2api-gpt-5-6-sol-text', true, '2026-07-27 16:52:54.635302+08', '2026-07-27 16:52:54.635302+08');
INSERT INTO public.feature_model_policy (id, feature_code, capability, default_deployment_code, allow_user_selection, created_at, updated_at) VALUES ('17290a44-6cb6-4a76-a5f6-86ee430fce2b', 'document.summary', 'VISION', 'codex2api-gpt-5-6-sol-vision', true, '2026-07-27 16:52:54.635302+08', '2026-07-27 16:52:54.635302+08');


--
-- Data for Name: feature_version; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["topic"], "properties": {"tone": {"enum": ["professional", "concise", "friendly", "creative"], "type": "string", "title": "表达语气", "default": "professional"}, "topic": {"type": "string", "title": "写作主题", "maxLength": 500, "minLength": 1}, "length": {"enum": ["short", "medium", "long"], "type": "string", "title": "篇幅", "default": "medium"}, "audience": {"type": "string", "title": "目标读者", "maxLength": 200}}, "additionalProperties": false}', '{"order": ["topic", "audience", "tone", "length"], "widgets": {"tone": "segmented", "topic": "textarea", "length": "segmented", "audience": "text"}, "enumLabels": {"tone": {"concise": "简洁", "creative": "创意", "friendly": "亲切", "professional": "专业"}, "length": {"long": "长", "short": "短", "medium": "中等"}}}', '{"type": "object", "required": ["format", "text"], "properties": {"text": {"type": "string"}, "format": {"const": "markdown"}}}', '{"modelAlias": "text.default", "capabilities": ["TEXT_GENERATION"], "maxOutputTokens": 2000}', '2026-07-27 16:52:54.099889+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('30000000-0000-0000-0000-000000000100', '20000000-0000-0000-0000-000000000100', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["prompt", "aspectRatio"], "properties": {"prompt": {"type": "string", "title": "画面描述", "maxLength": 500, "minLength": 1, "description": "描述主体、场景、风格、构图和光线。"}, "aspectRatio": {"enum": ["1:1", "16:9", "9:16"], "type": "string", "title": "图片比例", "default": "1:1", "description": "选择生成图片的横竖比例。"}, "referenceImages": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "参考图片", "maxItems": 3, "description": "可选上传最多 3 张主体、构图或风格参考图。"}}, "additionalProperties": false}', '{"order": ["prompt", "referenceImages", "aspectRatio"], "widgets": {"prompt": "textarea", "aspectRatio": "segmented", "referenceImages": "image"}, "feeNotice": "生成图片将调用付费模型，费用按所选模型实际计费。点击“生成图片”即表示确认本次调用。", "enumLabels": {"aspectRatio": {"1:1": "1:1", "16:9": "16:9", "9:16": "9:16"}}, "submitLabel": "生成图片", "fieldOptions": {"referenceImages": {"maxItems": 3, "showPreview": true, "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 31457280}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxReferenceImages": 3, "maxReferenceImageBytes": 10485760, "maxReferenceImagesTotalBytes": 31457280}', '2026-07-27 16:52:54.27643+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('ddfb3d08-5bd1-402c-809e-2e16604409fc', '85435eee-bf94-4bed-a7d5-5d349c9bbba1', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["mode", "sourceText"], "properties": {"mode": {"enum": ["rewrite", "polish"], "type": "string", "title": "处理方式", "default": "rewrite", "description": "改写会调整措辞、句式和段落结构；润色会尽量保留原结构。"}, "sourceText": {"type": "string", "title": "原文内容", "maxLength": 2000, "minLength": 1, "description": "输入需要改写或润色的纯文本，输出将保持原文语言。"}}, "additionalProperties": false}', '{"order": ["mode", "sourceText"], "actions": {"showReset": true}, "widgets": {"mode": "segmented", "sourceText": "textarea"}, "examples": {"sourceText": "我们团队最近完成了产品的新版本开发，这个版本加入了多个实用功能，也解决了一些之前存在的问题，希望能够给用户带来更好的使用体验。"}, "enumLabels": {"mode": {"polish": "润色", "rewrite": "改写"}}}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "text"], "properties": {"text": {"type": "string", "minLength": 1}, "format": {"const": "markdown"}}, "additionalProperties": false}', '{"modelAlias": "text.default", "capabilities": ["TEXT_GENERATION"], "maxOutputTokens": 3000, "revisionSourceField": "sourceText"}', '2026-07-27 16:52:54.287156+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('c1299460-36ab-4a99-85cd-a79530f5d197', '85435eee-bf94-4bed-a7d5-5d349c9bbba1', 2, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["mode", "sourceText"], "properties": {"mode": {"enum": ["rewrite", "polish"], "type": "string", "title": "处理方式", "default": "rewrite", "description": "改写会调整措辞、句式和段落结构；润色会尽量保留原结构。"}, "sourceText": {"type": "string", "title": "原文内容", "maxLength": 2000, "minLength": 1, "description": "输入需要改写或润色的纯文本，输出将保持原文语言。"}, "polishRequirements": {"type": "string", "title": "润色需求", "maxLength": 500, "description": "可选，例如表达更自然、更专业、修正标点或改善衔接。"}, "rewriteRequirements": {"type": "string", "title": "改写需求", "maxLength": 500, "description": "可选，例如更口语化、压缩篇幅、增强节奏或保留幽默感。"}}, "additionalProperties": false}', '{"order": ["mode", "sourceText", "rewriteRequirements", "polishRequirements"], "actions": {"showReset": true}, "widgets": {"mode": "segmented", "sourceText": "textarea", "polishRequirements": "textarea", "rewriteRequirements": "textarea"}, "examples": {"sourceText": "我们团队最近完成了产品的新版本开发，这个版本加入了多个实用功能，也解决了一些之前存在的问题，希望能够给用户带来更好的使用体验。"}, "enumLabels": {"mode": {"polish": "润色", "rewrite": "改写"}}, "visibility": {"polishRequirements": {"field": "mode", "equals": "polish"}, "rewriteRequirements": {"field": "mode", "equals": "rewrite"}}}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "text"], "properties": {"text": {"type": "string", "minLength": 1}, "format": {"const": "markdown"}}, "additionalProperties": false}', '{"modelAlias": "text.default", "capabilities": ["TEXT_GENERATION"], "maxOutputTokens": 3000, "revisionSourceField": "sourceText"}', '2026-07-27 16:52:54.295221+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('dc94b9b5-4cc9-45b0-8dbe-a6bd4dc5c8d0', 'a2d3a2fc-9b0f-4d2a-8d78-31b706f4eb2f', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["sourceText", "targetLanguage"], "properties": {"sourceText": {"type": "string", "title": "原文内容", "maxLength": 2000, "minLength": 1, "description": "输入需要翻译的纯文本，系统将自动识别源语言。"}, "targetLanguage": {"enum": ["zh-CN", "zh-TW", "en", "ja", "ko", "fr", "de", "es", "ru", "ar"], "type": "string", "title": "目标语言", "default": "en", "description": "选择译文使用的语言。"}}, "additionalProperties": false}', '{"order": ["sourceText", "targetLanguage"], "widgets": {"sourceText": "textarea", "targetLanguage": "select"}, "feeNotice": "翻译将调用所选文本模型，可能产生费用；点击“开始翻译”即表示确认本次调用。", "enumLabels": {"targetLanguage": {"ar": "阿拉伯语", "de": "德语", "en": "英语", "es": "西班牙语", "fr": "法语", "ja": "日语", "ko": "韩语", "ru": "俄语", "zh-CN": "简体中文", "zh-TW": "繁体中文"}}, "submitLabel": "开始翻译", "revisionSubmitLabel": "重新翻译"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "text"], "properties": {"text": {"type": "string", "minLength": 1}, "format": {"const": "plain_text"}}, "additionalProperties": false}', '{"modelAlias": "text.default", "capabilities": ["TEXT_GENERATION"], "maxOutputTokens": 3000}', '2026-07-27 16:52:54.30246+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('98fd543f-b065-4db3-b932-029711867857', '3e43c7cb-6f6a-4d02-a415-c28e2a0e2c93', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["articleTitle", "thesis", "style"], "properties": {"style": {"enum": ["professional", "concise", "friendly", "creative"], "type": "string", "title": "表达风格", "default": "professional"}, "thesis": {"type": "string", "title": "文章主旨", "maxLength": 1000, "minLength": 1, "description": "说明文章想表达的中心思想和写作目标。"}, "operation": {"enum": ["generate", "regenerate", "save_edit"], "type": "string", "default": "generate"}, "editedText": {"type": "string", "maxLength": 10000}, "articleTitle": {"type": "string", "title": "文章标题", "maxLength": 200, "minLength": 1, "description": "输入计划撰写的文章标题。"}}, "additionalProperties": false}', '{"order": ["articleTitle", "thesis", "style"], "widgets": {"style": "segmented", "thesis": "textarea", "articleTitle": "text"}, "feeNotice": "生成写作框架将调用所选文本模型，可能产生费用；点击“生成框架”即表示确认本次调用。", "enumLabels": {"style": {"concise": "简洁", "creative": "创意", "friendly": "亲切", "professional": "专业"}}, "submitLabel": "生成框架", "revisionSubmitLabel": "生成新框架"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "text"], "properties": {"text": {"type": "string", "minLength": 1}, "format": {"const": "plain_text"}}, "additionalProperties": false}', '{"modelAlias": "text.default", "capabilities": ["TEXT_GENERATION"], "maxOutputTokens": 2000, "maxEditedCharacters": 10000}', '2026-07-27 16:52:54.314875+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('92635ecb-cc61-49de-89df-4528eb80701f', 'd72718f0-b5d2-4a2d-8e02-2af0ec5f4e2a', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["mode", "sourceImage"], "properties": {"mode": {"enum": ["remove_background", "replace_background"], "type": "string", "title": "处理方式", "default": "remove_background", "description": "抠图会移除背景并输出透明 PNG；换背景可使用文字描述、参考图或两者结合。"}, "sourceImage": {"type": "string", "title": "主体原图", "format": "uuid", "description": "上传需要识别主体并处理背景的图片；最长边不超过 8192 像素。"}, "backgroundImage": {"type": "string", "title": "背景参考图", "format": "uuid", "description": "可选。上传希望使用或参考的背景图片。"}, "backgroundDescription": {"type": "string", "title": "背景描述", "maxLength": 500, "description": "可选。描述目标背景、光线、氛围和需要保留的阴影；可与背景参考图一起使用。"}}, "additionalProperties": false}', '{"order": ["mode", "sourceImage", "backgroundImage", "backgroundDescription"], "widgets": {"mode": "segmented", "sourceImage": "image", "backgroundImage": "image", "backgroundDescription": "textarea"}, "feeNotice": "图片处理将调用付费模型，费用按所选模型实际计费。点击“开始处理”即表示确认本次调用。", "enumLabels": {"mode": {"remove_background": "抠图", "replace_background": "换背景"}}, "visibility": {"backgroundImage": {"field": "mode", "equals": "replace_background"}, "backgroundDescription": {"field": "mode", "equals": "replace_background"}}, "submitLabel": "开始处理", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传主体原图", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}, "backgroundImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传背景参考图", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxImagePixels": 40000000, "maxImageDimension": 8192, "maxSourceImageBytes": 10485760, "maxBackgroundImageBytes": 10485760, "maxInputImagesTotalBytes": 20971520, "preserveSourceDimensions": true, "revisionSourceAssetField": "sourceImage"}', '2026-07-27 16:52:54.332534+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('b7d0f14e-2ce6-4e6e-8eb4-58c8b938d1f2', 'd72718f0-b5d2-4a2d-8e02-2af0ec5f4e2a', 2, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["mode", "sourceImage"], "properties": {"mode": {"enum": ["remove_background", "replace_background"], "type": "string", "title": "处理方式", "default": "remove_background", "description": "抠图会移除背景并输出透明 PNG；换背景可使用文字描述、参考图或两者结合。"}, "sourceImage": {"type": "string", "title": "第一张：主体原图", "format": "uuid", "description": "必传。上传需要识别主体并处理背景的 PNG 或 JPG 图片；最长边不超过 8192 像素。"}, "backgroundImage": {"type": "string", "title": "第二张：背景参考图", "format": "uuid", "description": "换背景时可选。上传希望使用或参考的 PNG 或 JPG 背景图片；也可以只填写背景描述。"}, "backgroundDescription": {"type": "string", "title": "背景描述", "maxLength": 500, "description": "可选。描述目标背景、光线、氛围和需要保留的阴影；可与背景参考图一起使用。"}}, "additionalProperties": false}', '{"order": ["mode", "sourceImage", "backgroundImage", "backgroundDescription"], "widgets": {"mode": "segmented", "sourceImage": "image", "backgroundImage": "image", "backgroundDescription": "textarea"}, "feeNotice": "图片处理将调用付费模型，费用按所选模型实际计费。点击“开始处理”即表示确认本次调用。", "enumLabels": {"mode": {"remove_background": "抠图", "replace_background": "换背景"}}, "visibility": {"backgroundImage": {"field": "mode", "equals": "replace_background"}, "backgroundDescription": {"field": "mode", "equals": "replace_background"}}, "submitLabel": "开始处理", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传主体原图", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg"], "allowedExtensions": [".png", ".jpg", ".jpeg"], "maxTotalSizeBytes": 10485760}, "backgroundImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传背景参考图", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg"], "allowedExtensions": [".png", ".jpg", ".jpeg"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxImagePixels": 40000000, "maxImageDimension": 8192, "maxSourceImageBytes": 10485760, "maxBackgroundImageBytes": 10485760, "maxInputImagesTotalBytes": 20971520, "preserveSourceDimensions": true, "revisionSourceAssetField": "sourceImage"}', '2026-07-27 16:52:54.339844+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('5f9e46d1-2203-4a88-9600-000000000002', '20000000-0000-0000-0000-000000000100', 2, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["prompt", "aspectRatio"], "properties": {"prompt": {"type": "string", "title": "画面描述", "maxLength": 500, "minLength": 1, "description": "描述主体、场景、风格、构图和光线。"}, "aspectRatio": {"enum": ["1:1", "16:9", "9:16"], "type": "string", "title": "图片比例", "default": "1:1", "description": "选择生成图片的横竖比例。"}, "referenceImages": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "参考图片", "maxItems": 3, "description": "可选上传最多 3 张主体、构图或风格参考图。"}}, "additionalProperties": false}', '{"order": ["prompt", "referenceImages", "aspectRatio"], "widgets": {"prompt": "textarea", "aspectRatio": "segmented", "referenceImages": "image"}, "feeNotice": "生成图片将调用付费模型，费用按所选模型实际计费。点击“生成图片”即表示确认本次调用。", "enumLabels": {"aspectRatio": {"1:1": "1:1", "16:9": "16:9", "9:16": "9:16"}}, "submitLabel": "生成图片", "fieldOptions": {"referenceImages": {"maxItems": 3, "showPreview": true, "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 31457280}}, "promptAssist": {"fields": {"prompt": {"contextFields": ["referenceImages", "aspectRatio"]}}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxReferenceImages": 3, "maxReferenceImageBytes": 10485760, "maxReferenceImagesTotalBytes": 31457280}', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('f4f8c5d7-2d5a-47ad-9c91-f38fb6e27796', 'd72718f0-b5d2-4a2d-8e02-2af0ec5f4e2a', 3, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["mode", "sourceImage"], "properties": {"mode": {"enum": ["remove_background", "replace_background"], "type": "string", "title": "处理方式", "default": "remove_background", "description": "抠图会移除背景并输出透明 PNG；换背景可使用文字描述、参考图或两者结合。"}, "sourceImage": {"type": "string", "title": "第一张：主体原图", "format": "uuid", "description": "必传。上传需要识别主体并处理背景的 PNG 或 JPG 图片；最长边不超过 8192 像素。"}, "backgroundImage": {"type": "string", "title": "第二张：背景参考图", "format": "uuid", "description": "换背景时可选。上传希望使用或参考的 PNG 或 JPG 背景图片；也可以只填写背景描述。"}, "backgroundDescription": {"type": "string", "title": "背景描述", "maxLength": 500, "description": "可选。描述目标背景、光线、氛围和需要保留的阴影；可与背景参考图一起使用。"}}, "additionalProperties": false}', '{"order": ["mode", "sourceImage", "backgroundImage", "backgroundDescription"], "widgets": {"mode": "segmented", "sourceImage": "image", "backgroundImage": "image", "backgroundDescription": "textarea"}, "feeNotice": "抠图会连续调用所选图片模型 2 次生成白底图和黑底图；换背景调用 1 次。费用按所选模型实际计费，点击“开始处理”即表示确认本次调用。", "enumLabels": {"mode": {"remove_background": "抠图", "replace_background": "换背景"}}, "visibility": {"backgroundImage": {"field": "mode", "equals": "replace_background"}, "backgroundDescription": {"field": "mode", "equals": "replace_background"}}, "submitLabel": "开始处理", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传主体原图", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg"], "allowedExtensions": [".png", ".jpg", ".jpeg"], "maxTotalSizeBytes": 10485760}, "backgroundImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传背景参考图", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg"], "allowedExtensions": [".png", ".jpg", ".jpeg"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxImagePixels": 40000000, "alphaExtraction": "black_white_difference", "maxImageDimension": 8192, "maxSourceImageBytes": 10485760, "maxBackgroundImageBytes": 10485760, "maxInputImagesTotalBytes": 20971520, "preserveSourceDimensions": true, "revisionSourceAssetField": "sourceImage", "removeBackgroundModelInvocationCount": 2}', '2026-07-27 16:52:54.345605+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('d43e4a82-55fc-4252-9c24-7045f560cfb3', '2dfe60b8-f04f-469e-b4a2-adb446a44b58', 1, '{"type": "object", "allOf": [{"if": {"required": ["mode"], "properties": {"mode": {"const": "upscale"}}}, "then": {"required": ["scale"]}}], "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["mode", "sourceImage"], "properties": {"mode": {"enum": ["upscale", "deblur", "denoise", "old_photo_restore"], "type": "string", "title": "处理方式", "default": "upscale", "description": "每次只执行一种图片清晰化或修复处理。"}, "scale": {"enum": ["2x", "4x"], "type": "string", "title": "放大倍率", "default": "2x", "description": "最终宽高严格放大为原图的 2 倍或 4 倍。"}, "colorize": {"type": "boolean", "title": "为黑白照片上色", "default": false, "description": "仅在老照片修复时使用；关闭时保持原来的黑白或彩色状态。"}, "sourceImage": {"type": "string", "title": "原始图片", "format": "uuid", "description": "上传需要提升清晰度或修复的单张图片。"}}, "additionalProperties": false}', '{"order": ["mode", "sourceImage", "scale", "colorize"], "widgets": {"mode": "segmented", "scale": "segmented", "colorize": "boolean", "sourceImage": "image"}, "feeNotice": "图片修复将调用所选付费模型，费用按实际模型计费；点击“开始修复”即表示确认本次调用。", "enumLabels": {"mode": {"deblur": "去模糊", "denoise": "图片降噪", "upscale": "图片放大", "old_photo_restore": "老照片修复"}, "scale": {"2x": "2x", "4x": "4x"}}, "visibility": {"scale": {"field": "mode", "equals": "upscale"}, "colorize": {"field": "mode", "equals": "old_photo_restore"}}, "submitLabel": "开始修复", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传原始图片", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxImagePixels": 40000000, "upscaleFactors": [2, 4], "maxImageDimension": 8192, "maxOutputImageBytes": 52428800, "maxSourceImageBytes": 10485760, "revisionSourceAssetField": "sourceImage"}', '2026-07-27 16:52:54.352787+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('fbfa220a-3e66-45c3-9c38-9bcf7d8a23f3', '2dfe60b8-f04f-469e-b4a2-adb446a44b58', 2, '{"type": "object", "allOf": [{"if": {"required": ["mode"], "properties": {"mode": {"const": "upscale"}}}, "then": {"required": ["scale"]}}], "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["mode", "sourceImage"], "properties": {"mode": {"enum": ["upscale", "deblur", "denoise", "old_photo_restore"], "type": "string", "title": "处理方式", "default": "upscale", "description": "每次只执行一种图片清晰化或修复处理。"}, "scale": {"enum": ["2x", "4x"], "type": "string", "title": "放大倍率", "default": "2x", "description": "最终宽高严格放大为原图的 2 倍或 4 倍。"}, "colorize": {"type": "boolean", "title": "为黑白照片上色", "default": false, "description": "仅在老照片修复时使用；关闭时保持原来的黑白或彩色状态。"}, "sourceImage": {"type": "string", "title": "原始图片", "format": "uuid", "description": "上传需要提升清晰度或修复的单张图片。"}}, "additionalProperties": false}', '{"order": ["mode", "sourceImage", "scale", "colorize"], "widgets": {"mode": "segmented", "scale": "segmented", "colorize": "boolean", "sourceImage": "image"}, "feeNotice": "老照片修复开启上色时会连续调用所选模型 2 次，其余处理方式调用 1 次；费用按实际模型计费，点击“开始修复”即表示确认本次调用。", "enumLabels": {"mode": {"deblur": "去模糊", "denoise": "图片降噪", "upscale": "图片放大", "old_photo_restore": "老照片修复"}, "scale": {"2x": "2x", "4x": "4x"}}, "visibility": {"scale": {"field": "mode", "equals": "upscale"}, "colorize": {"field": "mode", "equals": "old_photo_restore"}}, "submitLabel": "开始修复", "fieldOptions": {"mode": {"compact": true, "labelMaxLines": 1, "showSelectedIcon": false}, "sourceImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传原始图片", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxImagePixels": 40000000, "upscaleFactors": [2, 4], "maxImageDimension": 8192, "maxOutputImageBytes": 52428800, "maxSourceImageBytes": 10485760, "colorizationStrategy": "restoration_then_full_colorization", "revisionSourceAssetField": "sourceImage", "oldPhotoRestoreModelInvocationCount": 1, "oldPhotoColorizeModelInvocationCount": 2}', '2026-07-27 16:52:54.358224+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('5b620ad6-4013-48a8-9091-d12b40b5874b', 'af9b8384-1ca6-4cc8-888d-a71b6965ea50', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["sourceImage", "preservationMode", "ratioMode"], "properties": {"ratioMode": {"enum": ["preset", "custom"], "type": "string", "title": "比例方式", "default": "preset"}, "sourceImage": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "原图", "maxItems": 1, "minItems": 1, "description": "上传一张需要扩展画布的图片。"}, "preservationMode": {"enum": ["strict", "flexible"], "type": "string", "title": "保真方式", "default": "strict", "description": "严格保留会确保原图区域像素不变；自然重绘允许模型轻微调整原图区域。"}, "customAspectRatio": {"type": "string", "title": "自定义比例", "pattern": "^[1-9][0-9]{0,2}:[1-9][0-9]{0,2}$", "maxLength": 7, "minLength": 3, "description": "输入宽:高，例如 7:5；支持范围为 1:3 至 3:1。"}, "presetAspectRatio": {"enum": ["1:1", "3:4", "16:9", "9:16", "4:5"], "type": "string", "title": "目标比例", "default": "1:1", "description": "选择扩图后的画布比例。"}}, "additionalProperties": false}', '{"order": ["sourceImage", "preservationMode", "ratioMode", "presetAspectRatio", "customAspectRatio"], "widgets": {"ratioMode": "segmented", "sourceImage": "image", "preservationMode": "segmented", "customAspectRatio": "text", "presetAspectRatio": "select"}, "feeNotice": "扩图将调用付费图片模型，费用按实际调用计费。点击“开始扩图”即表示确认本次调用。", "enumLabels": {"ratioMode": {"custom": "自定义", "preset": "预设比例"}, "preservationMode": {"strict": "严格保留", "flexible": "自然重绘"}, "presetAspectRatio": {"1:1": "1:1", "3:4": "3:4", "4:5": "4:5", "16:9": "16:9", "9:16": "9:16"}}, "visibility": {"customAspectRatio": {"field": "ratioMode", "equals": "custom"}, "presetAspectRatio": {"field": "ratioMode", "equals": "preset"}}, "submitLabel": "开始扩图", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxSourceImages": 1, "maxSourceImageBytes": 10485760, "revisionUsesBaseArtifactImage": true}', '2026-07-27 16:52:54.364304+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('f953d99e-a01b-4b5f-b11f-b2061782b337', 'af9b8384-1ca6-4cc8-888d-a71b6965ea50', 2, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["sourceImage", "preservationMode", "ratioMode", "expansionScaleMode"], "properties": {"ratioMode": {"enum": ["preset", "custom"], "type": "string", "title": "比例方式", "default": "preset"}, "sourceImage": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "原图", "maxItems": 1, "minItems": 1, "description": "上传一张需要扩展画布的图片。"}, "preservationMode": {"enum": ["strict", "flexible"], "type": "string", "title": "保真方式", "default": "strict", "description": "严格保留会确保原图区域像素不变；自然重绘允许模型轻微调整原图区域。"}, "customAspectRatio": {"type": "string", "title": "自定义比例", "pattern": "^[1-9][0-9]{0,2}:[1-9][0-9]{0,2}$", "maxLength": 7, "minLength": 3, "description": "输入宽:高，例如 7:5；支持范围为 1:3 至 3:1。"}, "presetAspectRatio": {"enum": ["1:1", "3:4", "16:9", "9:16", "4:5"], "type": "string", "title": "目标比例", "default": "1:1", "description": "选择扩图后的画布比例。"}, "expansionScaleMode": {"enum": ["preset", "custom"], "type": "string", "title": "倍数方式", "default": "preset"}, "customExpansionScale": {"type": "number", "title": "自定义倍数", "maximum": 3.0, "minimum": 1.0, "multipleOf": 0.05, "description": "输入 1.0 至 3.0，例如 1.8。"}, "presetExpansionScale": {"enum": ["1.0", "1.25", "1.5", "2.0"], "type": "string", "title": "扩展倍数", "default": "1.25", "description": "在最小目标比例画布基础上继续等比扩展。"}}, "additionalProperties": false}', '{"order": ["sourceImage", "preservationMode", "ratioMode", "presetAspectRatio", "customAspectRatio", "expansionScaleMode", "presetExpansionScale", "customExpansionScale"], "widgets": {"ratioMode": "segmented", "sourceImage": "image", "preservationMode": "segmented", "customAspectRatio": "text", "presetAspectRatio": "select", "expansionScaleMode": "segmented", "customExpansionScale": "number", "presetExpansionScale": "select"}, "feeNotice": "扩图将调用付费图片模型，费用按实际调用计费。点击“开始扩图”即表示确认本次调用。", "enumLabels": {"ratioMode": {"custom": "自定义", "preset": "预设比例"}, "preservationMode": {"strict": "严格保留", "flexible": "自然重绘"}, "presetAspectRatio": {"1:1": "1:1", "3:4": "3:4", "4:5": "4:5", "16:9": "16:9", "9:16": "9:16"}, "expansionScaleMode": {"custom": "自定义", "preset": "预设倍数"}, "presetExpansionScale": {"1.0": "1.0×", "1.5": "1.5×", "2.0": "2.0×", "1.25": "1.25×"}}, "visibility": {"customAspectRatio": {"field": "ratioMode", "equals": "custom"}, "presetAspectRatio": {"field": "ratioMode", "equals": "preset"}, "customExpansionScale": {"field": "expansionScaleMode", "equals": "custom"}, "presetExpansionScale": {"field": "expansionScaleMode", "equals": "preset"}}, "submitLabel": "开始扩图", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxSourceImages": 1, "maxExpansionScale": 3.0, "minExpansionScale": 1.0, "maxSourceImageBytes": 10485760, "revisionUsesBaseArtifactImage": true}', '2026-07-27 16:52:54.371464+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('da784b1c-3d5e-4a58-b66f-62298e5dc50c', 'af9b8384-1ca6-4cc8-888d-a71b6965ea50', 3, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["sourceImage", "preservationMode", "operationMode", "ratioMode", "expansionScaleMode"], "properties": {"ratioMode": {"enum": ["preset", "custom"], "type": "string", "title": "比例方式", "default": "preset", "description": "仅在改比例模式下使用。"}, "sourceImage": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "原图", "maxItems": 1, "minItems": 1, "description": "上传一张需要扩展画布的图片。"}, "operationMode": {"enum": ["change_ratio", "expand"], "type": "string", "title": "处理方式", "default": "change_ratio", "description": "改比例会扩展到指定画布比例；扩图会保持原图比例并按倍数向四周扩展。"}, "preservationMode": {"enum": ["strict", "flexible"], "type": "string", "title": "保真方式", "default": "strict", "description": "严格保留会确保原图区域像素不变；自然重绘允许模型轻微调整原图区域。"}, "customAspectRatio": {"type": "string", "title": "自定义比例", "pattern": "^[1-9][0-9]{0,2}:[1-9][0-9]{0,2}$", "maxLength": 7, "minLength": 3, "description": "输入宽:高，例如 7:5；支持范围为 1:3 至 3:1。"}, "presetAspectRatio": {"enum": ["1:1", "3:4", "16:9", "9:16", "4:5"], "type": "string", "title": "目标比例", "default": "1:1", "description": "选择扩图后的画布比例。"}, "expansionScaleMode": {"enum": ["preset", "custom"], "type": "string", "title": "倍数方式", "default": "preset", "description": "仅在扩图模式下使用。"}, "customExpansionScale": {"type": "number", "title": "自定义倍数", "maximum": 3.0, "minimum": 1.0, "multipleOf": 0.05, "description": "输入 1.0 至 3.0，例如 1.8。"}, "presetExpansionScale": {"enum": ["1.0", "1.25", "1.5", "2.0"], "type": "string", "title": "扩展倍数", "default": "1.25", "description": "保持原图宽高比，将画布宽度和高度按该倍数向四周扩展。"}}, "additionalProperties": false}', '{"order": ["sourceImage", "preservationMode", "operationMode", "ratioMode", "presetAspectRatio", "customAspectRatio", "expansionScaleMode", "presetExpansionScale", "customExpansionScale"], "widgets": {"ratioMode": "segmented", "sourceImage": "image", "operationMode": "segmented", "preservationMode": "segmented", "customAspectRatio": "text", "presetAspectRatio": "select", "expansionScaleMode": "segmented", "customExpansionScale": "number", "presetExpansionScale": "select"}, "feeNotice": "图片处理将调用付费图片模型，费用按实际调用计费。点击“开始生成”即表示确认本次调用。", "enumLabels": {"ratioMode": {"custom": "自定义", "preset": "预设比例"}, "operationMode": {"expand": "扩图", "change_ratio": "改比例"}, "preservationMode": {"strict": "严格保留", "flexible": "自然重绘"}, "presetAspectRatio": {"1:1": "1:1", "3:4": "3:4", "4:5": "4:5", "16:9": "16:9", "9:16": "9:16"}, "expansionScaleMode": {"custom": "自定义", "preset": "预设倍数"}, "presetExpansionScale": {"1.0": "1.0×", "1.5": "1.5×", "2.0": "2.0×", "1.25": "1.25×"}}, "visibility": {"ratioMode": {"field": "operationMode", "equals": "change_ratio"}, "customAspectRatio": {"all": [{"field": "operationMode", "equals": "change_ratio"}, {"field": "ratioMode", "equals": "custom"}]}, "presetAspectRatio": {"all": [{"field": "operationMode", "equals": "change_ratio"}, {"field": "ratioMode", "equals": "preset"}]}, "expansionScaleMode": {"field": "operationMode", "equals": "expand"}, "customExpansionScale": {"all": [{"field": "operationMode", "equals": "expand"}, {"field": "expansionScaleMode", "equals": "custom"}]}, "presetExpansionScale": {"all": [{"field": "operationMode", "equals": "expand"}, {"field": "expansionScaleMode", "equals": "preset"}]}}, "submitLabel": "开始生成", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "operationModes": ["change_ratio", "expand"], "maxSourceImages": 1, "maxExpansionScale": 3.0, "minExpansionScale": 1.0, "maxSourceImageBytes": 10485760, "changeRatioExpansionScale": 1.0, "expandUsesSourceAspectRatio": true, "revisionUsesBaseArtifactImage": true}', '2026-07-27 16:52:54.378908+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('8b3d752f-3988-46b8-8c7a-e25c49fa92f6', 'af9b8384-1ca6-4cc8-888d-a71b6965ea50', 4, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["sourceImage", "preservationMode", "operationMode", "ratioMode", "expansionScaleMode"], "properties": {"ratioMode": {"enum": ["preset", "custom"], "type": "string", "title": "比例方式", "default": "preset", "description": "仅在改比例模式下使用。"}, "sourceImage": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "原图", "maxItems": 1, "minItems": 1, "description": "上传一张需要扩展画布的图片。"}, "operationMode": {"enum": ["change_ratio", "expand"], "type": "string", "title": "处理方式", "default": "change_ratio", "description": "改比例会扩展到指定画布比例；扩图会保持原图比例并按倍数向四周扩展。"}, "preservationMode": {"enum": ["strict", "flexible"], "type": "string", "title": "保真方式", "default": "strict", "description": "严格保留会确保原图区域像素不变；自然重绘允许模型轻微调整原图区域。"}, "customAspectRatio": {"type": "string", "title": "自定义比例", "pattern": "^[1-9][0-9]{0,2}:[1-9][0-9]{0,2}$", "maxLength": 7, "minLength": 3, "description": "输入宽:高，例如 7:5；支持范围为 1:3 至 3:1。"}, "presetAspectRatio": {"enum": ["1:1", "3:4", "16:9", "9:16", "4:5"], "type": "string", "title": "目标比例", "default": "1:1", "description": "选择扩图后的画布比例。"}, "expansionScaleMode": {"enum": ["preset", "custom"], "type": "string", "title": "倍数方式", "default": "preset", "description": "仅在扩图模式下使用。"}, "customExpansionScale": {"type": "number", "title": "自定义倍数", "minimum": 1.0, "multipleOf": 0.05, "description": "输入不小于 1.0 的倍数，例如 1.8；可用上限由所选模型和原图尺寸决定。"}, "presetExpansionScale": {"enum": ["1.0", "1.25", "1.5", "2.0"], "type": "string", "title": "扩展倍数", "default": "1.0", "description": "保持原图宽高比，将画布宽度和高度按该倍数向四周扩展。"}}, "additionalProperties": false}', '{"order": ["sourceImage", "preservationMode", "operationMode", "ratioMode", "presetAspectRatio", "customAspectRatio", "expansionScaleMode", "presetExpansionScale", "customExpansionScale"], "widgets": {"ratioMode": "segmented", "sourceImage": "image", "operationMode": "segmented", "preservationMode": "segmented", "customAspectRatio": "text", "presetAspectRatio": "select", "expansionScaleMode": "segmented", "customExpansionScale": "number", "presetExpansionScale": "select"}, "feeNotice": "图片处理将调用付费图片模型，费用按实际调用计费。点击“开始生成”即表示确认本次调用。", "enumLabels": {"ratioMode": {"custom": "自定义", "preset": "预设比例"}, "operationMode": {"expand": "扩图", "change_ratio": "改比例"}, "preservationMode": {"strict": "严格保留", "flexible": "自然重绘"}, "presetAspectRatio": {"1:1": "1:1", "3:4": "3:4", "4:5": "4:5", "16:9": "16:9", "9:16": "9:16"}, "expansionScaleMode": {"custom": "自定义", "preset": "预设倍数"}, "presetExpansionScale": {"1.0": "1.0×", "1.5": "1.5×", "2.0": "2.0×", "1.25": "1.25×"}}, "visibility": {"ratioMode": {"field": "operationMode", "equals": "change_ratio"}, "customAspectRatio": {"all": [{"field": "operationMode", "equals": "change_ratio"}, {"field": "ratioMode", "equals": "custom"}]}, "presetAspectRatio": {"all": [{"field": "operationMode", "equals": "change_ratio"}, {"field": "ratioMode", "equals": "preset"}]}, "expansionScaleMode": {"field": "operationMode", "equals": "expand"}, "customExpansionScale": {"all": [{"field": "operationMode", "equals": "expand"}, {"field": "expansionScaleMode", "equals": "custom"}]}, "presetExpansionScale": {"all": [{"field": "operationMode", "equals": "expand"}, {"field": "expansionScaleMode", "equals": "preset"}]}}, "submitLabel": "开始生成", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "operationModes": ["change_ratio", "expand"], "maxSourceImages": 1, "minExpansionScale": 1.0, "expansionScaleLimit": "selected-model", "maxSourceImageBytes": 10485760, "defaultExpansionScale": 1.0, "changeRatioExpansionScale": 1.0, "expandUsesSourceAspectRatio": true, "revisionUsesBaseArtifactImage": true}', '2026-07-27 16:52:54.387756+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('9d01ec61-ffdd-4b8a-9041-e94567de6ea5', 'af9b8384-1ca6-4cc8-888d-a71b6965ea50', 5, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["sourceImage", "preservationMode", "operationMode", "ratioMode", "expansionScaleMode"], "properties": {"ratioMode": {"enum": ["preset", "custom"], "type": "string", "title": "比例方式", "default": "preset", "description": "仅在改比例模式下使用。"}, "sourceImage": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "原图", "maxItems": 1, "minItems": 1, "description": "上传一张需要扩展画布的图片。"}, "operationMode": {"enum": ["change_ratio", "expand"], "type": "string", "title": "处理方式", "default": "change_ratio", "description": "改比例会扩展到指定画布比例；扩图会保持原图比例并按倍数向四周扩展。"}, "preservationMode": {"enum": ["strict", "flexible"], "type": "string", "title": "保真方式", "default": "strict", "description": "严格保留会确保原图区域像素不变；自然重绘允许模型轻微调整原图区域。"}, "customAspectRatio": {"type": "string", "title": "自定义比例", "pattern": "^[1-9][0-9]{0,2}:[1-9][0-9]{0,2}$", "maxLength": 7, "minLength": 3, "description": "输入宽:高，例如 7:5；支持范围为 1:3 至 3:1。"}, "presetAspectRatio": {"enum": ["1:1", "3:4", "16:9", "9:16", "4:5"], "type": "string", "title": "目标比例", "default": "1:1", "description": "选择扩图后的画布比例。"}, "expansionScaleMode": {"enum": ["preset", "custom"], "type": "string", "title": "倍数方式", "default": "preset", "description": "仅在扩图模式下使用。"}, "customExpansionScale": {"type": "number", "title": "自定义倍数", "minimum": 1.0, "multipleOf": 0.05, "description": "输入不小于 1.0 的倍数，例如 1.8；可用上限由所选模型和原图尺寸决定。"}, "presetExpansionScale": {"enum": ["1.0", "1.25", "1.5", "2.0"], "type": "string", "title": "扩展倍数", "default": "1.0", "description": "保持原图宽高比，将画布宽度和高度按该倍数向四周扩展。"}}, "additionalProperties": false}', '{"order": ["sourceImage", "preservationMode", "operationMode", "ratioMode", "presetAspectRatio", "customAspectRatio", "expansionScaleMode", "presetExpansionScale", "customExpansionScale"], "widgets": {"ratioMode": "segmented", "sourceImage": "image", "operationMode": "segmented", "preservationMode": "segmented", "customAspectRatio": "text", "presetAspectRatio": "select", "expansionScaleMode": "segmented", "customExpansionScale": "number", "presetExpansionScale": "select"}, "feeNotice": "图片处理将调用付费图片模型，费用按实际调用计费。点击“开始生成”即表示确认本次调用。", "fieldHelp": {"operationMode": {"text": "该选项会给改比例后的图片进行填充处理", "tone": "danger", "when": {"field": "operationMode", "equals": "change_ratio"}}}, "enumLabels": {"ratioMode": {"custom": "自定义", "preset": "预设比例"}, "operationMode": {"expand": "扩图", "change_ratio": "改比例"}, "preservationMode": {"strict": "严格保留", "flexible": "自然重绘"}, "presetAspectRatio": {"1:1": "1:1", "3:4": "3:4", "4:5": "4:5", "16:9": "16:9", "9:16": "9:16"}, "expansionScaleMode": {"custom": "自定义", "preset": "预设倍数"}, "presetExpansionScale": {"1.0": "1.0×", "1.5": "1.5×", "2.0": "2.0×", "1.25": "1.25×"}}, "visibility": {"ratioMode": {"field": "operationMode", "equals": "change_ratio"}, "customAspectRatio": {"all": [{"field": "operationMode", "equals": "change_ratio"}, {"field": "ratioMode", "equals": "custom"}]}, "presetAspectRatio": {"all": [{"field": "operationMode", "equals": "change_ratio"}, {"field": "ratioMode", "equals": "preset"}]}, "expansionScaleMode": {"field": "operationMode", "equals": "expand"}, "customExpansionScale": {"all": [{"field": "operationMode", "equals": "expand"}, {"field": "expansionScaleMode", "equals": "custom"}]}, "presetExpansionScale": {"all": [{"field": "operationMode", "equals": "expand"}, {"field": "expansionScaleMode", "equals": "preset"}]}}, "submitLabel": "开始生成", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "operationModes": ["change_ratio", "expand"], "maxSourceImages": 1, "minExpansionScale": 1.0, "expansionScaleLimit": "selected-model", "maxSourceImageBytes": 10485760, "defaultExpansionScale": 1.0, "changeRatioSubjectLock": true, "changeRatioExpansionScale": 1.0, "expandUsesSourceAspectRatio": true, "revisionUsesBaseArtifactImage": true, "changeRatioFillOutsideSourceOnly": true, "changeRatioEffectivePreservationMode": "strict"}', '2026-07-27 16:52:54.400742+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('3d18ddd6-d06d-407f-aaf6-d5eac9fa9dcc', '3ecffe9d-176d-462a-9a62-f14969905676', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["sourceImage", "maskImage", "instruction"], "properties": {"maskImage": {"type": "string", "title": "编辑区域", "format": "uuid", "description": "在原图上涂抹允许模型修改的区域，未涂抹区域会保留原图像素。"}, "instruction": {"type": "string", "title": "修改指令", "maxLength": 500, "minLength": 1, "description": "说明涂抹区域需要变成什么，避免要求修改选区外内容。"}, "sourceImage": {"type": "string", "title": "原始图片", "format": "uuid", "description": "上传需要局部修改的 PNG、JPG、JPEG 或 WebP 图片。"}}, "additionalProperties": false}', '{"order": ["sourceImage", "maskImage", "instruction"], "widgets": {"maskImage": "image_mask", "instruction": "textarea", "sourceImage": "image"}, "feeNotice": "图片局部编辑会调用 1 次 GPT Image 2 付费模型。点击“开始编辑”即表示确认本次调用。", "submitLabel": "开始编辑", "fieldOptions": {"maskImage": {"maxItems": 1, "editorLabel": "涂抹编辑区域", "showPreview": true, "sourceField": "sourceImage", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png"], "allowedExtensions": [".png"], "maxTotalSizeBytes": 10485760}, "sourceImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传原始图片", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maskSemantics": "transparent_is_edit", "maxImagePixels": 40000000, "maxImageDimension": 8192, "maxMaskImageBytes": 10485760, "maxOutputImageBytes": 52428800, "maxSourceImageBytes": 10485760, "revisionResetFields": ["maskImage"], "preserveUnmaskedPixels": true, "maxInputImagesTotalBytes": 20971520, "revisionSourceAssetField": "sourceImage"}', '2026-07-27 16:52:54.407511+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('73aa021b-59d7-4e0a-8f92-31658e22d158', '3ecffe9d-176d-462a-9a62-f14969905676', 2, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["sourceImage", "maskImage", "instruction"], "properties": {"maskImage": {"type": "string", "title": "编辑区域", "format": "uuid", "description": "在原图上涂抹允许模型修改的区域，未涂抹区域会保留原图像素。"}, "instruction": {"type": "string", "title": "选区修改内容", "maxLength": 500, "minLength": 1, "description": "涂抹区域决定允许修改的范围；这里只描述选区内需要变成什么。"}, "sourceImage": {"type": "string", "title": "原始图片", "format": "uuid", "description": "上传需要局部修改的 PNG、JPG、JPEG 或 WebP 图片。"}}, "additionalProperties": false}', '{"order": ["sourceImage", "maskImage", "instruction"], "widgets": {"maskImage": "image_mask", "instruction": "textarea", "sourceImage": "image"}, "feeNotice": "图片局部编辑会调用 1 次 GPT Image 2 付费模型。点击“开始编辑”即表示确认本次调用。", "submitLabel": "开始编辑", "fieldOptions": {"maskImage": {"maxItems": 1, "editorLabel": "在原图上涂抹编辑区域", "showPreview": true, "sourceField": "sourceImage", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png"], "allowedExtensions": [".png"], "maxTotalSizeBytes": 10485760}, "sourceImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传原始图片", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maskSemantics": "transparent_is_edit", "maxImagePixels": 40000000, "maxImageDimension": 8192, "maxMaskImageBytes": 10485760, "maxOutputImageBytes": 52428800, "maxSourceImageBytes": 10485760, "revisionResetFields": ["maskImage"], "preserveUnmaskedPixels": true, "maxInputImagesTotalBytes": 20971520, "revisionSourceAssetField": "sourceImage"}', '2026-07-27 16:52:54.416537+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('5f9e46d1-2203-4a88-9600-000000000003', '3ecffe9d-176d-462a-9a62-f14969905676', 3, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["sourceImage", "maskImage", "instruction"], "properties": {"maskImage": {"type": "string", "title": "编辑区域", "format": "uuid", "description": "在原图上涂抹允许模型修改的区域，未涂抹区域会保留原图像素。"}, "instruction": {"type": "string", "title": "选区修改内容", "maxLength": 500, "minLength": 1, "description": "涂抹区域决定允许修改的范围；这里只描述选区内需要变成什么。"}, "sourceImage": {"type": "string", "title": "原始图片", "format": "uuid", "description": "上传需要局部修改的 PNG、JPG、JPEG 或 WebP 图片。"}}, "additionalProperties": false}', '{"order": ["sourceImage", "maskImage", "instruction"], "widgets": {"maskImage": "image_mask", "instruction": "textarea", "sourceImage": "image"}, "feeNotice": "图片局部编辑会调用 1 次 GPT Image 2 付费模型。点击“开始编辑”即表示确认本次调用。", "submitLabel": "开始编辑", "fieldOptions": {"maskImage": {"maxItems": 1, "editorLabel": "在原图上涂抹编辑区域", "showPreview": true, "sourceField": "sourceImage", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png"], "allowedExtensions": [".png"], "maxTotalSizeBytes": 10485760}, "sourceImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传原始图片", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 10485760}}, "promptAssist": {"fields": {"instruction": {"contextFields": ["sourceImage", "maskImage"]}}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maskSemantics": "transparent_is_edit", "maxImagePixels": 40000000, "maxImageDimension": 8192, "maxMaskImageBytes": 10485760, "maxOutputImageBytes": 52428800, "maxSourceImageBytes": 10485760, "revisionResetFields": ["maskImage"], "preserveUnmaskedPixels": true, "maxInputImagesTotalBytes": 20971520, "revisionSourceAssetField": "sourceImage"}', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('5f9e46d1-2203-4a88-9600-000000000004', 'd72718f0-b5d2-4a2d-8e02-2af0ec5f4e2a', 4, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["mode", "sourceImage"], "properties": {"mode": {"enum": ["remove_background", "replace_background"], "type": "string", "title": "处理方式", "default": "remove_background", "description": "抠图会移除背景并输出透明 PNG；换背景可使用文字描述、参考图或两者结合。"}, "sourceImage": {"type": "string", "title": "第一张：主体原图", "format": "uuid", "description": "必传。上传需要识别主体并处理背景的 PNG 或 JPG 图片；最长边不超过 8192 像素。"}, "backgroundImage": {"type": "string", "title": "第二张：背景参考图", "format": "uuid", "description": "换背景时可选。上传希望使用或参考的 PNG 或 JPG 背景图片；也可以只填写背景描述。"}, "backgroundDescription": {"type": "string", "title": "背景描述", "maxLength": 500, "description": "可选。描述目标背景、光线、氛围和需要保留的阴影；可与背景参考图一起使用。"}}, "additionalProperties": false}', '{"order": ["mode", "sourceImage", "backgroundImage", "backgroundDescription"], "widgets": {"mode": "segmented", "sourceImage": "image", "backgroundImage": "image", "backgroundDescription": "textarea"}, "feeNotice": "抠图会连续调用所选图片模型 2 次生成白底图和黑底图；换背景调用 1 次。费用按所选模型实际计费，点击“开始处理”即表示确认本次调用。", "enumLabels": {"mode": {"remove_background": "抠图", "replace_background": "换背景"}}, "visibility": {"backgroundImage": {"field": "mode", "equals": "replace_background"}, "backgroundDescription": {"field": "mode", "equals": "replace_background"}}, "submitLabel": "开始处理", "fieldOptions": {"sourceImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传主体原图", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg"], "allowedExtensions": [".png", ".jpg", ".jpeg"], "maxTotalSizeBytes": 10485760}, "backgroundImage": {"maxItems": 1, "showPreview": true, "uploadLabel": "上传背景参考图", "maxFileSizeBytes": 10485760, "acceptedMimeTypes": ["image/png", "image/jpeg"], "allowedExtensions": [".png", ".jpg", ".jpeg"], "maxTotalSizeBytes": 10485760}}, "promptAssist": {"fields": {"backgroundDescription": {"contextFields": ["mode", "backgroundImage"]}}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxImagePixels": 40000000, "alphaExtraction": "black_white_difference", "maxImageDimension": 8192, "maxSourceImageBytes": 10485760, "maxBackgroundImageBytes": 10485760, "maxInputImagesTotalBytes": 20971520, "preserveSourceDimensions": true, "revisionSourceAssetField": "sourceImage", "removeBackgroundModelInvocationCount": 2}', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('5f9e46d1-2203-4a88-9600-000000000005', '20000000-0000-0000-0000-000000000001', 2, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["topic"], "properties": {"tone": {"enum": ["professional", "concise", "friendly", "creative"], "type": "string", "title": "表达语气", "default": "professional"}, "topic": {"type": "string", "title": "写作主题", "maxLength": 500, "minLength": 1}, "length": {"enum": ["short", "medium", "long"], "type": "string", "title": "篇幅", "default": "medium"}, "audience": {"type": "string", "title": "目标读者", "maxLength": 200}}, "additionalProperties": false}', '{"order": ["topic", "audience", "tone", "length"], "widgets": {"tone": "segmented", "topic": "textarea", "length": "segmented", "audience": "text"}, "enumLabels": {"tone": {"concise": "简洁", "creative": "创意", "friendly": "亲切", "professional": "专业"}, "length": {"long": "长", "short": "短", "medium": "中等"}}, "promptAssist": {"fields": {"topic": {"contextFields": ["audience", "tone", "length"]}}}}', '{"type": "object", "required": ["format", "text"], "properties": {"text": {"type": "string"}, "format": {"const": "markdown"}}}', '{"modelAlias": "text.default", "capabilities": ["TEXT_GENERATION"], "maxOutputTokens": 2000}', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('5f9e46d1-2203-4a88-9600-000000000006', '3e43c7cb-6f6a-4d02-a415-c28e2a0e2c93', 2, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["articleTitle", "thesis", "style"], "properties": {"style": {"enum": ["professional", "concise", "friendly", "creative"], "type": "string", "title": "表达风格", "default": "professional"}, "thesis": {"type": "string", "title": "文章主旨", "maxLength": 1000, "minLength": 1, "description": "说明文章想表达的中心思想和写作目标。"}, "operation": {"enum": ["generate", "regenerate", "save_edit"], "type": "string", "default": "generate"}, "editedText": {"type": "string", "maxLength": 10000}, "articleTitle": {"type": "string", "title": "文章标题", "maxLength": 200, "minLength": 1, "description": "输入计划撰写的文章标题。"}}, "additionalProperties": false}', '{"order": ["articleTitle", "thesis", "style"], "widgets": {"style": "segmented", "thesis": "textarea", "articleTitle": "text"}, "feeNotice": "生成写作框架将调用所选文本模型，可能产生费用；点击“生成框架”即表示确认本次调用。", "enumLabels": {"style": {"concise": "简洁", "creative": "创意", "friendly": "亲切", "professional": "专业"}}, "submitLabel": "生成框架", "promptAssist": {"fields": {"thesis": {"contextFields": ["articleTitle", "style"]}}}, "revisionSubmitLabel": "生成新框架"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "text"], "properties": {"text": {"type": "string", "minLength": 1}, "format": {"const": "plain_text"}}, "additionalProperties": false}', '{"modelAlias": "text.default", "capabilities": ["TEXT_GENERATION"], "maxOutputTokens": 2000, "maxEditedCharacters": 10000}', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('5f9e46d1-2203-4a88-9600-000000000007', '85435eee-bf94-4bed-a7d5-5d349c9bbba1', 3, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["mode", "sourceText"], "properties": {"mode": {"enum": ["rewrite", "polish"], "type": "string", "title": "处理方式", "default": "rewrite", "description": "改写会调整措辞、句式和段落结构；润色会尽量保留原结构。"}, "sourceText": {"type": "string", "title": "原文内容", "maxLength": 2000, "minLength": 1, "description": "输入需要改写或润色的纯文本，输出将保持原文语言。"}, "polishRequirements": {"type": "string", "title": "润色需求", "maxLength": 500, "description": "可选，例如表达更自然、更专业、修正标点或改善衔接。"}, "rewriteRequirements": {"type": "string", "title": "改写需求", "maxLength": 500, "description": "可选，例如更口语化、压缩篇幅、增强节奏或保留幽默感。"}}, "additionalProperties": false}', '{"order": ["mode", "sourceText", "rewriteRequirements", "polishRequirements"], "actions": {"showReset": true}, "widgets": {"mode": "segmented", "sourceText": "textarea", "polishRequirements": "textarea", "rewriteRequirements": "textarea"}, "examples": {"sourceText": "我们团队最近完成了产品的新版本开发，这个版本加入了多个实用功能，也解决了一些之前存在的问题，希望能够给用户带来更好的使用体验。"}, "enumLabels": {"mode": {"polish": "润色", "rewrite": "改写"}}, "visibility": {"polishRequirements": {"field": "mode", "equals": "polish"}, "rewriteRequirements": {"field": "mode", "equals": "rewrite"}}, "promptAssist": {"fields": {"polishRequirements": {"contextFields": ["mode", "sourceText"]}, "rewriteRequirements": {"contextFields": ["mode", "sourceText"]}}}}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "text"], "properties": {"text": {"type": "string", "minLength": 1}, "format": {"const": "markdown"}}, "additionalProperties": false}', '{"modelAlias": "text.default", "capabilities": ["TEXT_GENERATION"], "maxOutputTokens": 3000, "revisionSourceField": "sourceText"}', '2026-07-27 16:52:54.465666+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('ad24b8c2-aa5d-48a6-82a8-264868996bd5', '20000000-0000-0000-0000-000000000100', 3, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["prompt", "aspectRatio"], "properties": {"prompt": {"type": "string", "title": "画面描述", "maxLength": 500, "minLength": 1, "description": "描述主体、场景、风格、构图和光线。"}, "aspectRatio": {"enum": ["1:1", "16:9", "9:16"], "type": "string", "title": "图片比例", "default": "1:1", "description": "选择生成图片的横竖比例。"}, "referenceImages": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "参考图片", "maxItems": 3, "description": "可选上传最多 3 张主体、构图或风格参考图。"}}, "additionalProperties": false}', '{"order": ["prompt", "referenceImages", "aspectRatio"], "widgets": {"prompt": "textarea", "aspectRatio": "segmented", "referenceImages": "image"}, "feeNotice": "生成图片将调用付费模型，费用按所选模型实际计费。点击“生成图片”即表示确认本次调用。", "enumLabels": {"aspectRatio": {"1:1": "1:1", "16:9": "16:9", "9:16": "9:16"}}, "submitLabel": "生成图片", "fieldOptions": {"referenceImages": {"maxItems": 3, "showPreview": true, "maxFileSizeBytes": 20971520, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 31457280}}, "promptAssist": {"fields": {"prompt": {"contextFields": ["referenceImages", "aspectRatio"]}}}, "revisionSubmitLabel": "生成新版本"}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxReferenceImages": 3, "maxReferenceImageBytes": 20971520, "maxReferenceImagesTotalBytes": 31457280}', '2026-07-27 16:52:54.573089+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('77201c4c-d0d0-452c-80d8-dde5f315581f', '20000000-0000-0000-0000-000000000100', 4, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["prompt", "aspectRatio"], "properties": {"prompt": {"type": "string", "title": "画面描述", "maxLength": 500, "minLength": 1, "description": "描述主体、场景、风格、构图和光线。"}, "aspectRatio": {"enum": ["1:1", "16:9", "9:16"], "type": "string", "title": "图片比例", "default": "1:1", "description": "选择生成图片的横竖比例。"}, "referenceImages": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "自行上传参考图", "maxItems": 3, "description": "可选上传最多 3 张主体、构图或风格参考图；与上一版生成成果分开管理。"}, "generatedReferenceMode": {"enum": ["NONE", "USE_BASE"], "type": "string", "title": "上一版成果", "default": "NONE", "description": "继续修改时决定是否将上一版生成成果作为额外参考图。"}}, "additionalProperties": false}', '{"order": ["prompt", "referenceImages", "generatedReferenceMode", "aspectRatio"], "widgets": {"prompt": "textarea", "aspectRatio": "segmented", "referenceImages": "image", "generatedReferenceMode": "hidden"}, "feeNotice": "生成图片将调用付费模型，费用按所选模型实际计费。点击“生成图片”即表示确认本次调用。", "enumLabels": {"aspectRatio": {"1:1": "1:1", "16:9": "16:9", "9:16": "9:16"}}, "submitLabel": "生成图片", "fieldOptions": {"referenceImages": {"maxItems": 3, "showPreview": true, "maxFileSizeBytes": 20971520, "acceptedMimeTypes": ["image/png", "image/jpeg", "image/webp"], "allowedExtensions": [".png", ".jpg", ".jpeg", ".webp"], "maxTotalSizeBytes": 31457280, "requiresReferenceImageSupport": true}}, "promptAssist": {"fields": {"prompt": {"contextFields": ["referenceImages", "aspectRatio"]}}}, "revisionSubmitLabel": "生成新版本", "revisionArtifactReference": {"title": "上一版成果", "modeField": "generatedReferenceMode", "description": "默认作为额外参考图，可点击叉号仅在本次生成中移除。", "enabledValue": "USE_BASE", "disabledValue": "NONE", "defaultEnabled": true}}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["assetId"], "properties": {"assetId": {"type": "string", "format": "uuid"}, "revisedPrompts": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false}', '{"modelAlias": "image.generation.default", "outputCount": 1, "capabilities": ["IMAGE_GENERATION"], "maxReferenceImages": 3, "maxReferenceImageBytes": 20971520, "maxTotalReferenceImages": 4, "defaultRevisionReferenceMode": "USE_BASE", "maxReferenceImagesTotalBytes": 31457280}', '2026-07-27 16:52:54.573089+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('30000000-0000-0000-0000-000000000021', '20000000-0000-0000-0000-000000000021', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["documents", "question"], "properties": {"question": {"type": "string", "title": "输入问题", "maxLength": 4000, "minLength": 1}, "documents": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "问答文档", "maxItems": 10, "minItems": 1, "uniqueItems": true}, "strictGrounding": {"type": "boolean", "const": true, "default": true}}, "additionalProperties": false}', '{"order": ["documents", "question", "strictGrounding"], "pageKey": "document_qa", "widgets": {"question": "textarea", "documents": "file", "strictGrounding": "hidden"}, "feeNotice": "文档解析、扫描页识别、图表理解、检索重排和回答可能产生模型费用。文档内容将发送给所选中转模型处理。", "submitLabel": "开始问答", "fieldOptions": {"documents": {"maxItems": 10, "maxFileSizeBytes": 52428800, "acceptedMimeTypes": ["application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/plain", "text/markdown", "text/csv", "application/json", "application/octet-stream"], "allowedExtensions": [".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md", ".csv", ".json"], "maxTotalSizeBytes": 209715200}}}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "question", "answerMarkdown", "citations", "contextTurns", "warnings"], "properties": {"format": {"const": "document_chat"}, "question": {"type": "string", "minLength": 1}, "warnings": {"type": "array", "items": {"type": "string"}}, "citations": {"type": "array", "items": {"type": "object", "required": ["marker", "assetId", "fileName", "excerpt", "locator"], "properties": {"marker": {"type": "string", "pattern": "^S[1-9][0-9]*$"}, "assetId": {"type": "string", "format": "uuid"}, "excerpt": {"type": "string", "maxLength": 600, "minLength": 1}, "locator": {"oneOf": [{"type": "object", "required": ["type", "pageNumber"], "properties": {"type": {"const": "PDF_PAGE"}, "pageNumber": {"type": "integer", "minimum": 1}}, "additionalProperties": false}, {"type": "object", "required": ["type", "paragraphStart", "paragraphEnd"], "properties": {"type": {"const": "WORD_PARAGRAPH"}, "visual": {"type": "boolean"}, "heading": {"type": "string", "maxLength": 500}, "paragraphEnd": {"type": "integer", "minimum": 1}, "paragraphStart": {"type": "integer", "minimum": 1}}, "additionalProperties": false}, {"type": "object", "required": ["type", "sheetName", "startRow", "endRow"], "properties": {"type": {"const": "EXCEL_ROWS"}, "endRow": {"type": "integer", "minimum": 1}, "startRow": {"type": "integer", "minimum": 1}, "sheetName": {"type": "string", "maxLength": 255, "minLength": 1}, "chartIndex": {"type": "integer", "minimum": 1}}, "additionalProperties": false}, {"type": "object", "required": ["type", "slideNumber"], "properties": {"type": {"const": "PPT_SLIDE"}, "slideNumber": {"type": "integer", "minimum": 1}}, "additionalProperties": false}, {"type": "object", "required": ["type", "startLine", "endLine"], "properties": {"type": {"const": "TEXT_LINES"}, "endLine": {"type": "integer", "minimum": 1}, "startLine": {"type": "integer", "minimum": 1}}, "additionalProperties": false}]}, "fileName": {"type": "string", "maxLength": 500, "minLength": 1}}, "additionalProperties": false}}, "contextTurns": {"type": "array", "items": {"type": "object", "required": ["question", "answer"], "properties": {"answer": {"type": "string", "minLength": 1}, "question": {"type": "string", "maxLength": 4000, "minLength": 1}}, "additionalProperties": false}, "maxItems": 20}, "answerMarkdown": {"type": "string", "minLength": 1}}, "additionalProperties": false}', '{"pageKey": "document_qa", "maxFiles": 10, "modelAliases": {"VISION": "document.qa.vision", "TEXT_GENERATION": "document.qa.text"}, "modelBundles": [{"code": "gpt-5.6-sol", "description": "质量优先，适合复杂文档、扫描页和图表问答。", "displayName": "GPT-5.6 Sol", "selectedModels": {"VISION": "codex2api-gpt-5-6-sol-vision", "TEXT_GENERATION": "codex2api-gpt-5-6-sol-text"}}, {"code": "gpt-5.4-mini", "description": "速度与成本优先，适合常规文档问答。", "displayName": "GPT-5.4 Mini", "selectedModels": {"VISION": "codex2api-gpt-5-4-mini-vision", "TEXT_GENERATION": "codex2api-gpt-5-4-mini-text"}}], "retrievalMode": "BM25_GPT_RERANK", "maxContextTurns": 20, "strictGrounding": true, "maxFileSizeBytes": 52428800, "maxQuestionLength": 4000, "maxTotalSizeBytes": 209715200}', '2026-07-27 16:52:54.583467+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('9395fa75-ad3c-4f52-ac95-dac8598fd05f', '25d807ac-1958-477c-b60e-2186ef946f5e', 1, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["document", "summaryDepth"], "properties": {"focus": {"type": "string", "title": "关注重点", "maxLength": 500, "description": "可选。说明需要重点关注的问题、数据或决策信息。"}, "document": {"type": "string", "title": "待总结文档", "format": "uuid", "description": "每次处理 1 个 PDF、Word、Excel 或 UTF-8 CSV 文档。"}, "summaryDepth": {"enum": ["concise", "standard", "detailed"], "type": "string", "title": "总结深度", "default": "standard", "description": "控制摘要和章节要点的展开程度。"}}, "additionalProperties": false}', '{"order": ["document", "summaryDepth", "focus"], "widgets": {"focus": "textarea", "document": "file", "summaryDepth": "segmented"}, "feeNotice": "文档总结会调用所选付费模型；扫描 PDF 将改用同一模型家族的视觉能力。点击“开始总结”即表示确认本次调用。", "fieldHelp": {"document": {"text": "正文抽取后最多 15 万字符；超出时请拆分文档。扫描 PDF 会使用所选模型识别页面文字。CSV 首版仅支持 UTF-8 或 UTF-8 BOM。"}}, "enumLabels": {"summaryDepth": {"concise": "简洁", "detailed": "详细", "standard": "标准"}}, "submitLabel": "开始总结", "fieldOptions": {"document": {"maxItems": 1, "showPreview": false, "uploadLabel": "选择并上传文档", "maxFileSizeBytes": 52428800, "acceptedMimeTypes": ["application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/plain", "application/csv", "text/comma-separated-values", "application/zip", "application/x-zip-compressed", "application/octet-stream"], "allowedExtensions": [".pdf", ".doc", ".docx", ".xls", ".xlsx", ".csv"], "maxTotalSizeBytes": 52428800}, "summaryDepth": {"compact": true, "labelMaxLines": 1, "showSelectedIcon": false}}, "revisionSubmitLabel": "生成新版本", "modelSelectionGroups": [{"key": "documentModel", "label": "文档处理模型", "options": [{"value": "gpt-5.6-sol", "deployments": {"VISION": "codex2api-gpt-5-6-sol-vision", "TEXT_GENERATION": "codex2api-gpt-5-6-sol-text"}, "description": "默认模型，适合长文档和复杂结构总结。", "displayName": "GPT-5.6 Sol"}, {"value": "gpt-5.4-mini", "deployments": {"VISION": "codex2api-gpt-5-4-mini-vision", "TEXT_GENERATION": "codex2api-gpt-5-4-mini-text"}, "description": "响应更轻量，适合结构清晰的常规文档。", "displayName": "GPT-5.4 Mini"}], "description": "正文总结和扫描页识别使用同一模型家族。", "capabilities": ["TEXT_GENERATION", "VISION"]}]}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "text"], "properties": {"text": {"type": "string", "minLength": 1}, "format": {"const": "markdown"}}, "additionalProperties": false}', '{"csvEncoding": "UTF-8", "capabilities": ["TEXT_GENERATION", "VISION"], "modelAliases": {"VISION": "vision.document-ocr", "TEXT_GENERATION": "text.document-summary"}, "maxInputFiles": 1, "maxInputFileBytes": 52428800, "maxFocusCharacters": 500, "maxExtractedCharacters": 150000}', '2026-07-27 16:52:54.635302+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('5ae3d62d-9ed9-4db5-ae93-b63c256a81f1', '25d807ac-1958-477c-b60e-2186ef946f5e', 2, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["document", "summaryDepth"], "properties": {"focus": {"type": "string", "title": "关注重点", "maxLength": 500, "description": "可选。说明需要重点关注的问题、数据或决策信息。"}, "document": {"type": "string", "title": "待总结文档", "format": "uuid", "description": "每次处理 1 个 PDF、Word、Excel、PowerPoint、Markdown、TXT、JSON 或 CSV 文档。"}, "summaryDepth": {"enum": ["concise", "standard", "detailed"], "type": "string", "title": "总结深度", "default": "standard", "description": "控制摘要和章节要点的展开程度。"}}, "additionalProperties": false}', '{"order": ["document", "summaryDepth", "focus"], "widgets": {"focus": "textarea", "document": "file", "summaryDepth": "segmented"}, "feeNotice": "文档总结会调用所选付费模型；扫描 PDF 将改用同一模型家族的视觉能力。点击“开始总结”即表示确认本次调用。", "fieldHelp": {"document": {"text": "正文抽取后最多 15 万字符；超出时请拆分文档。扫描 PDF 会使用所选模型识别页面文字。CSV、Markdown、TXT 和 JSON 仅支持 UTF-8 或 UTF-8 BOM。"}}, "enumLabels": {"summaryDepth": {"concise": "简洁", "detailed": "详细", "standard": "标准"}}, "submitLabel": "开始总结", "fieldOptions": {"document": {"maxItems": 1, "showPreview": false, "uploadLabel": "选择并上传文档", "maxFileSizeBytes": 52428800, "acceptedMimeTypes": ["application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/csv", "text/plain", "text/markdown", "text/x-markdown", "application/json", "text/json", "application/csv", "text/comma-separated-values", "application/zip", "application/x-zip-compressed", "application/octet-stream"], "allowedExtensions": [".pdf", ".doc", ".docx", ".xls", ".xlsx", ".csv", ".md", ".markdown", ".txt", ".json", ".ppt", ".pptx"], "maxTotalSizeBytes": 52428800}, "summaryDepth": {"compact": true, "labelMaxLines": 1, "showSelectedIcon": false}}, "revisionSubmitLabel": "生成新版本", "modelSelectionGroups": [{"key": "documentModel", "label": "文档处理模型", "options": [{"value": "gpt-5.6-sol", "deployments": {"VISION": "codex2api-gpt-5-6-sol-vision", "TEXT_GENERATION": "codex2api-gpt-5-6-sol-text"}, "description": "默认模型，适合长文档和复杂结构总结。", "displayName": "GPT-5.6 Sol"}, {"value": "gpt-5.4-mini", "deployments": {"VISION": "codex2api-gpt-5-4-mini-vision", "TEXT_GENERATION": "codex2api-gpt-5-4-mini-text"}, "description": "响应更轻量，适合结构清晰的常规文档。", "displayName": "GPT-5.4 Mini"}], "description": "正文总结和扫描页识别使用同一模型家族。", "capabilities": ["TEXT_GENERATION", "VISION"]}]}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "text"], "properties": {"text": {"type": "string", "minLength": 1}, "format": {"const": "markdown"}}, "additionalProperties": false}', '{"csvEncoding": "UTF-8", "capabilities": ["TEXT_GENERATION", "VISION"], "modelAliases": {"VISION": "vision.document-ocr", "TEXT_GENERATION": "text.document-summary"}, "textEncoding": "UTF-8", "maxInputFiles": 1, "maxInputFileBytes": 52428800, "maxFocusCharacters": 500, "maxExtractedCharacters": 150000}', '2026-07-27 16:52:54.643829+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('30000000-0000-0000-0000-000000000022', '20000000-0000-0000-0000-000000000021', 2, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["documents", "question"], "properties": {"question": {"type": "string", "title": "输入问题", "maxLength": 4000, "minLength": 1}, "documents": {"type": "array", "items": {"type": "string", "format": "uuid"}, "title": "问答文档", "maxItems": 10, "minItems": 1, "uniqueItems": true}, "strictGrounding": {"type": "boolean", "const": true, "default": true}}, "additionalProperties": false}', '{"order": ["documents", "question", "strictGrounding"], "pageKey": "document_qa", "widgets": {"question": "textarea", "documents": "file", "strictGrounding": "hidden"}, "feeNotice": "文档解析、扫描页识别、图表理解、检索重排和回答可能产生模型费用。文档内容将发送给所选中转模型处理。", "submitLabel": "开始问答", "fieldOptions": {"documents": {"maxItems": 10, "maxFileSizeBytes": 52428800, "acceptedMimeTypes": ["application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/plain", "text/markdown", "text/csv", "application/json", "application/octet-stream", "text/comma-separated-values"], "allowedExtensions": [".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md", ".csv", ".json"], "maxTotalSizeBytes": 209715200}}}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "question", "answerMarkdown", "citations", "contextTurns", "warnings"], "properties": {"format": {"const": "document_chat"}, "question": {"type": "string", "minLength": 1}, "warnings": {"type": "array", "items": {"type": "string"}}, "citations": {"type": "array", "items": {"type": "object", "required": ["marker", "assetId", "fileName", "excerpt", "locator"], "properties": {"marker": {"type": "string", "pattern": "^S[1-9][0-9]*$"}, "assetId": {"type": "string", "format": "uuid"}, "excerpt": {"type": "string", "maxLength": 600, "minLength": 1}, "locator": {"oneOf": [{"type": "object", "required": ["type", "pageNumber"], "properties": {"type": {"const": "PDF_PAGE"}, "pageNumber": {"type": "integer", "minimum": 1}}, "additionalProperties": false}, {"type": "object", "required": ["type", "paragraphStart", "paragraphEnd"], "properties": {"type": {"const": "WORD_PARAGRAPH"}, "visual": {"type": "boolean"}, "heading": {"type": "string", "maxLength": 500}, "paragraphEnd": {"type": "integer", "minimum": 1}, "paragraphStart": {"type": "integer", "minimum": 1}}, "additionalProperties": false}, {"type": "object", "required": ["type", "sheetName", "startRow", "endRow"], "properties": {"type": {"const": "EXCEL_ROWS"}, "endRow": {"type": "integer", "minimum": 1}, "startRow": {"type": "integer", "minimum": 1}, "sheetName": {"type": "string", "maxLength": 255, "minLength": 1}, "chartIndex": {"type": "integer", "minimum": 1}}, "additionalProperties": false}, {"type": "object", "required": ["type", "slideNumber"], "properties": {"type": {"const": "PPT_SLIDE"}, "slideNumber": {"type": "integer", "minimum": 1}}, "additionalProperties": false}, {"type": "object", "required": ["type", "startLine", "endLine"], "properties": {"type": {"const": "TEXT_LINES"}, "endLine": {"type": "integer", "minimum": 1}, "startLine": {"type": "integer", "minimum": 1}}, "additionalProperties": false}]}, "fileName": {"type": "string", "maxLength": 500, "minLength": 1}}, "additionalProperties": false}}, "contextTurns": {"type": "array", "items": {"type": "object", "required": ["question", "answer"], "properties": {"answer": {"type": "string", "minLength": 1}, "question": {"type": "string", "maxLength": 4000, "minLength": 1}}, "additionalProperties": false}, "maxItems": 20}, "answerMarkdown": {"type": "string", "minLength": 1}}, "additionalProperties": false}', '{"pageKey": "document_qa", "maxFiles": 10, "modelAliases": {"VISION": "document.qa.vision", "TEXT_GENERATION": "document.qa.text"}, "modelBundles": [{"code": "gpt-5.6-sol", "description": "质量优先，适合复杂文档、扫描页和图表问答。", "displayName": "GPT-5.6 Sol", "selectedModels": {"VISION": "codex2api-gpt-5-6-sol-vision", "TEXT_GENERATION": "codex2api-gpt-5-6-sol-text"}}, {"code": "gpt-5.4-mini", "description": "速度与成本优先，适合常规文档问答。", "displayName": "GPT-5.4 Mini", "selectedModels": {"VISION": "codex2api-gpt-5-4-mini-vision", "TEXT_GENERATION": "codex2api-gpt-5-4-mini-text"}}], "retrievalMode": "BM25_GPT_RERANK", "maxContextTurns": 20, "strictGrounding": true, "maxFileSizeBytes": 52428800, "maxQuestionLength": 4000, "maxTotalSizeBytes": 209715200}', '2026-07-27 16:52:54.650016+08');
INSERT INTO public.feature_version (id, feature_id, version, input_schema_json, ui_schema_json, output_schema_json, config_json, created_at) VALUES ('b63deab9-9105-4941-88c9-eb0c7e0f345d', '25d807ac-1958-477c-b60e-2186ef946f5e', 3, '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["document", "summaryDepth"], "properties": {"focus": {"type": "string", "title": "关注重点", "maxLength": 500, "description": "可选。说明需要重点关注的问题、数据或决策信息。"}, "document": {"type": "string", "title": "待总结文档", "format": "uuid", "description": "每次处理 1 个 PDF、Word、Excel、PowerPoint、Markdown、TXT、JSON 或 CSV 文档。"}, "summaryDepth": {"enum": ["concise", "standard", "detailed"], "type": "string", "title": "总结深度", "default": "standard", "description": "控制摘要和章节要点的展开程度。"}}, "additionalProperties": false}', '{"order": ["document", "summaryDepth", "focus"], "widgets": {"focus": "textarea", "document": "file", "summaryDepth": "segmented"}, "feeNotice": "文档总结会调用所选付费模型；扫描 PDF 将改用同一模型家族的视觉能力。点击“开始总结”即表示确认本次调用。", "fieldHelp": {"document": {"text": "正文抽取后最多 15 万字符；超出时请拆分文档。扫描 PDF 和图片型 PPT/PPTX 会使用所选模型识别页面；图片型演示文稿最多 30 页。CSV、Markdown、TXT 和 JSON 仅支持 UTF-8 或 UTF-8 BOM。"}}, "enumLabels": {"summaryDepth": {"concise": "简洁", "detailed": "详细", "standard": "标准"}}, "submitLabel": "开始总结", "fieldOptions": {"document": {"maxItems": 1, "showPreview": false, "uploadLabel": "选择并上传文档", "maxFileSizeBytes": 52428800, "acceptedMimeTypes": ["application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/csv", "text/plain", "text/markdown", "text/x-markdown", "application/json", "text/json", "application/csv", "text/comma-separated-values", "application/zip", "application/x-zip-compressed", "application/octet-stream"], "allowedExtensions": [".pdf", ".doc", ".docx", ".xls", ".xlsx", ".csv", ".md", ".markdown", ".txt", ".json", ".ppt", ".pptx"], "maxTotalSizeBytes": 52428800}, "summaryDepth": {"compact": true, "labelMaxLines": 1, "showSelectedIcon": false}}, "revisionSubmitLabel": "生成新版本", "modelSelectionGroups": [{"key": "documentModel", "label": "文档处理模型", "options": [{"value": "gpt-5.6-sol", "deployments": {"VISION": "codex2api-gpt-5-6-sol-vision", "TEXT_GENERATION": "codex2api-gpt-5-6-sol-text"}, "description": "默认模型，适合长文档和复杂结构总结。", "displayName": "GPT-5.6 Sol"}, {"value": "gpt-5.4-mini", "deployments": {"VISION": "codex2api-gpt-5-4-mini-vision", "TEXT_GENERATION": "codex2api-gpt-5-4-mini-text"}, "description": "响应更轻量，适合结构清晰的常规文档。", "displayName": "GPT-5.4 Mini"}], "description": "正文总结和扫描页识别使用同一模型家族。", "capabilities": ["TEXT_GENERATION", "VISION"]}]}', '{"type": "object", "$schema": "https://json-schema.org/draft/2020-12/schema", "required": ["format", "text"], "properties": {"text": {"type": "string", "minLength": 1}, "format": {"const": "markdown"}}, "additionalProperties": false}', '{"csvEncoding": "UTF-8", "capabilities": ["TEXT_GENERATION", "VISION"], "modelAliases": {"VISION": "vision.document-ocr", "TEXT_GENERATION": "text.document-summary"}, "textEncoding": "UTF-8", "maxInputFiles": 1, "maxInputFileBytes": 52428800, "maxFocusCharacters": 500, "maxExtractedCharacters": 150000, "maxVisualPresentationSlides": 30}', '2026-07-27 16:52:54.655239+08');


--
-- Data for Name: idempotency_record; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: job; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: model_deployment; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000002', 'zhipu-glm-5v-turbo-vision', 'zhipu-bigmodel', 'GLM-5V Turbo Vision', 'Image understanding and multimodal analysis model', 'VISION', 'glm-5v-turbo', true, false, '{}', '2026-07-27 16:52:54.183971+08', '2026-07-27 16:52:54.183971+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000006', 'codex2api-gpt-image-2-image', 'codex2api-relay', 'GPT Image 2', 'Image generation model through Codex2API relay', 'IMAGE_GENERATION', 'gpt-image-2', true, true, '{"source": "relay", "discovery": "v1/models", "imageSizeMap": {"1:1": "1024x1024", "16:9": "1536x864", "9:16": "864x1536"}, "maskPartName": "mask", "imagePartName": "image[]", "supportsImageMask": true, "maxReferenceImages": 4, "imageExpansionMaxEdge": 3840, "imageExpansionProtocol": "openai-edit", "imageExpansionMaxPixels": 8294400, "imageExpansionMinPixels": 1, "imageExpansionSupportsMask": true, "imageExpansionMaxUploadBytes": 52428800, "imageExpansionDimensionMultiple": 1, "imageExpansionScaleFromSourceDimensions": true}', '2026-07-27 16:52:54.259804+08', '2026-07-27 16:52:54.573089+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000004', 'codex2api-gpt-5-4-mini-text', 'codex2api-relay', 'GPT-5.4 Mini', '轻量均衡的文本生成模型，通过 Codex2API 中转服务调用。', 'TEXT_GENERATION', 'gpt-5.4-mini', true, true, '{"source": "relay", "discovery": "v1/models"}', '2026-07-27 16:52:54.259804+08', '2026-07-27 16:52:54.323754+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000005', 'codex2api-gpt-5-6-text', 'codex2api-relay', 'GPT-5.6', '高质量文本生成模型，通过 Codex2API 中转服务调用。', 'TEXT_GENERATION', 'gpt-5.6', true, true, '{"source": "relay", "discovery": "v1/models"}', '2026-07-27 16:52:54.259804+08', '2026-07-27 16:52:54.323754+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000003', 'aliyun-qwen-image-2-0', 'aliyun-maas', 'Qwen Image 2.0', 'General-purpose image generation model', 'IMAGE_GENERATION', 'qwen-image-2.0', true, true, '{"imageSizeMap": {"1:1": "1024x1024", "16:9": "1536x864", "9:16": "864x1536"}, "imageExpansionPath": "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", "maxReferenceImages": 0, "imageExpansionMaxEdge": 3072, "imageExpansionProtocol": "dashscope-multimodal", "imageExpansionMaxPixels": 4194304, "imageExpansionMinPixels": 262144, "imageExpansionSupportsMask": false, "imageExpansionMaxUploadBytes": 10485760, "imageExpansionDimensionMultiple": 16}', '2026-07-27 16:52:54.242582+08', '2026-07-27 16:52:54.573089+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000021', 'codex2api-gpt-5-6-sol-text', 'codex2api-relay', 'GPT-5.6 Sol', '高质量文档检索重排与回答模型', 'TEXT_GENERATION', 'gpt-5.6-sol', true, true, '{"source": "relay", "supportsStreamUsage": true}', '2026-07-27 16:52:54.583467+08', '2026-07-27 16:52:54.583467+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000022', 'codex2api-gpt-5-6-sol-vision', 'codex2api-relay', 'GPT-5.6 Sol 视觉', '扫描页、图片文字和复杂图表理解模型', 'VISION', 'gpt-5.6-sol', true, true, '{"source": "relay"}', '2026-07-27 16:52:54.583467+08', '2026-07-27 16:52:54.583467+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000023', 'codex2api-gpt-5-4-mini-vision', 'codex2api-relay', 'GPT-5.4 Mini 视觉', '更快、更节省的扫描页和图表理解模型', 'VISION', 'gpt-5.4-mini', true, true, '{"source": "relay"}', '2026-07-27 16:52:54.583467+08', '2026-07-27 16:52:54.583467+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000011', 'zhipu-glm-5-2-text', 'zhipu-bigmodel', 'GLM-5.2', 'Official Zhipu flagship text model for long-context writing tasks', 'TEXT_GENERATION', 'glm-5.2', true, true, '{"source": "official", "protocol": "openai-compatible", "discovery": "GET /models"}', '2026-07-27 16:52:54.453248+08', '2026-07-27 16:52:54.453248+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000012', 'zhipu-glm-4-5-air-text', 'zhipu-bigmodel', 'GLM-4.5-Air', 'Official Zhipu lightweight text model for cost-sensitive writing tasks', 'TEXT_GENERATION', 'glm-4.5-air', true, true, '{"source": "official", "protocol": "openai-compatible", "discovery": "GET /models"}', '2026-07-27 16:52:54.453248+08', '2026-07-27 16:52:54.453248+08');
INSERT INTO public.model_deployment (id, code, provider_code, display_name, description, capability, provider_model, enabled, selectable, config_json, created_at, updated_at) VALUES ('41000000-0000-0000-0000-000000000001', 'zhipu-glm-5v-turbo-text', 'zhipu-bigmodel', 'GLM-5V Turbo', 'General-purpose drafting and rewriting model', 'TEXT_GENERATION', 'glm-5v-turbo', false, false, '{}', '2026-07-27 16:52:54.183971+08', '2026-07-27 16:52:54.453248+08');


--
-- Data for Name: model_provider; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.model_provider (id, code, display_name, protocol, enabled, created_at, updated_at, provider_kind) VALUES ('40000000-0000-0000-0000-000000000002', 'aliyun-maas', 'Alibaba Cloud Model Studio', 'openai-compatible', true, '2026-07-27 16:52:54.242582+08', '2026-07-27 16:52:54.242582+08', 'OFFICIAL');
INSERT INTO public.model_provider (id, code, display_name, protocol, enabled, created_at, updated_at, provider_kind) VALUES ('40000000-0000-0000-0000-000000000003', 'codex2api-relay', 'Codex2API Relay', 'openai-compatible', true, '2026-07-27 16:52:54.259804+08', '2026-07-27 16:52:54.259804+08', 'RELAY');
INSERT INTO public.model_provider (id, code, display_name, protocol, enabled, created_at, updated_at, provider_kind) VALUES ('40000000-0000-0000-0000-000000000001', 'zhipu-bigmodel', 'Zhipu BigModel', 'openai-compatible', true, '2026-07-27 16:52:54.183971+08', '2026-07-27 16:52:54.453248+08', 'OFFICIAL');


--
-- Data for Name: model_route; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000002', 'vision.default', 'VISION', 'zhipu-glm-5v-turbo-vision', 10, true, '2026-07-27 16:52:54.183971+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000003', 'image.generation.default', 'IMAGE_GENERATION', 'aliyun-qwen-image-2-0', 10, true, '2026-07-27 16:52:54.242582+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000004', 'text.default', 'TEXT_GENERATION', 'codex2api-gpt-5-4-mini-text', 20, true, '2026-07-27 16:52:54.259804+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000005', 'text.default', 'TEXT_GENERATION', 'codex2api-gpt-5-6-text', 30, true, '2026-07-27 16:52:54.259804+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000006', 'image.generation.default', 'IMAGE_GENERATION', 'codex2api-gpt-image-2-image', 20, true, '2026-07-27 16:52:54.259804+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000001', 'text.default', 'TEXT_GENERATION', 'zhipu-glm-5v-turbo-text', 10, false, '2026-07-27 16:52:54.183971+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000011', 'text.default', 'TEXT_GENERATION', 'zhipu-glm-5-2-text', 10, true, '2026-07-27 16:52:54.453248+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000012', 'text.default', 'TEXT_GENERATION', 'zhipu-glm-4-5-air-text', 15, true, '2026-07-27 16:52:54.453248+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('5f9e46d1-2203-4a88-9600-000000000001', 'prompt.optimize.default', 'TEXT_GENERATION', 'codex2api-gpt-5-4-mini-text', 10, true, '2026-07-27 16:52:54.465666+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000021', 'document.qa.text', 'TEXT_GENERATION', 'codex2api-gpt-5-6-sol-text', 10, true, '2026-07-27 16:52:54.583467+08');
INSERT INTO public.model_route (id, model_alias, capability, deployment_code, priority, enabled, created_at) VALUES ('42000000-0000-0000-0000-000000000022', 'document.qa.vision', 'VISION', 'codex2api-gpt-5-6-sol-vision', 10, true, '2026-07-27 16:52:54.583467+08');


--
-- Data for Name: outbox_event; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: project; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: provider_invocation; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: run_output_event; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: run_output_stream; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: task; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: task_asset; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: task_run; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: task_run_asset; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: workspace; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.workspace (id, code, display_name, description, icon_key, groups_json, search_terms_json, sort_order, enabled, created_at) VALUES ('10000000-0000-0000-0000-000000000001', 'writing', '文本与写作', '同一编辑器内完成写、改、审', 'edit', '["create"]', '["文本", "文章", "文案", "报告", "润色", "翻译"]', 10, true, '2026-07-27 16:52:54.099889+08');
INSERT INTO public.workspace (id, code, display_name, description, icon_key, groups_json, search_terms_json, sort_order, enabled, created_at) VALUES ('10000000-0000-0000-0000-000000000002', 'presentation', 'PPT 与演示', '生成、转换和优化可交付的幻灯片', 'presentation', '["create"]', '["PPT", "演示", "幻灯片", "汇报", "大纲"]', 20, true, '2026-07-27 16:52:54.099889+08');
INSERT INTO public.workspace (id, code, display_name, description, icon_key, groups_json, search_terms_json, sort_order, enabled, created_at) VALUES ('10000000-0000-0000-0000-000000000004', 'audio', '音频', '识别、生成和处理声音文件', 'audio', '["process", "media"]', '["音频", "语音", "识别", "转写", "ASR", "配音", "降噪"]', 40, true, '2026-07-27 16:52:54.099889+08');
INSERT INTO public.workspace (id, code, display_name, description, icon_key, groups_json, search_terms_json, sort_order, enabled, created_at) VALUES ('10000000-0000-0000-0000-000000000005', 'video', '视频', '围绕画面与时间线完成生成和加工', 'video', '["create", "media"]', '["视频", "短视频", "剪辑", "字幕", "数字人"]', 50, true, '2026-07-27 16:52:54.099889+08');
INSERT INTO public.workspace (id, code, display_name, description, icon_key, groups_json, search_terms_json, sort_order, enabled, created_at) VALUES ('10000000-0000-0000-0000-000000000006', 'document', '文档与数据', '以文件为对象，阅读、提取和转换', 'document', '["process"]', '["PDF", "Word", "Excel", "文档", "数据", "提取", "表格"]', 60, true, '2026-07-27 16:52:54.099889+08');
INSERT INTO public.workspace (id, code, display_name, description, icon_key, groups_json, search_terms_json, sort_order, enabled, created_at) VALUES ('10000000-0000-0000-0000-000000000003', 'image', '图片设计', '生成与编辑共享同一设计画布', 'image', '["create", "process", "media"]', '["图片", "设计", "海报", "配图", "抠图", "扩图", "清晰", "修复", "放大", "去模糊", "降噪", "老照片", "局部编辑", "涂抹", "改图", "重绘"]', 30, true, '2026-07-27 16:52:54.099889+08');


--
-- Name: run_output_event_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.run_output_event_id_seq', 1, false);


--
-- Name: artifact_asset artifact_asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.artifact_asset
    ADD CONSTRAINT artifact_asset_pkey PRIMARY KEY (artifact_id, asset_id, role);


--
-- Name: artifact artifact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.artifact
    ADD CONSTRAINT artifact_pkey PRIMARY KEY (id);


--
-- Name: asset_blob asset_blob_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_blob
    ADD CONSTRAINT asset_blob_pkey PRIMARY KEY (id);


--
-- Name: asset_blob asset_blob_storage_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_blob
    ADD CONSTRAINT asset_blob_storage_key_key UNIQUE (storage_key);


--
-- Name: asset asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset
    ADD CONSTRAINT asset_pkey PRIMARY KEY (id);


--
-- Name: document_chunk document_chunk_document_index_id_ordinal_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_chunk
    ADD CONSTRAINT document_chunk_document_index_id_ordinal_key UNIQUE (document_index_id, ordinal);


--
-- Name: document_chunk document_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_chunk
    ADD CONSTRAINT document_chunk_pkey PRIMARY KEY (id);


--
-- Name: document_index document_index_asset_id_vision_deployment_code_parser_versi_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_index
    ADD CONSTRAINT document_index_asset_id_vision_deployment_code_parser_versi_key UNIQUE (asset_id, vision_deployment_code, parser_version);


--
-- Name: document_index document_index_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_index
    ADD CONSTRAINT document_index_pkey PRIMARY KEY (id);


--
-- Name: feature_definition feature_definition_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_definition
    ADD CONSTRAINT feature_definition_code_key UNIQUE (code);


--
-- Name: feature_definition feature_definition_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_definition
    ADD CONSTRAINT feature_definition_pkey PRIMARY KEY (id);


--
-- Name: feature_model_option feature_model_option_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_model_option
    ADD CONSTRAINT feature_model_option_pkey PRIMARY KEY (policy_id, deployment_code);


--
-- Name: feature_model_policy feature_model_policy_feature_code_capability_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_model_policy
    ADD CONSTRAINT feature_model_policy_feature_code_capability_key UNIQUE (feature_code, capability);


--
-- Name: feature_model_policy feature_model_policy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_model_policy
    ADD CONSTRAINT feature_model_policy_pkey PRIMARY KEY (id);


--
-- Name: feature_version feature_version_feature_id_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_version
    ADD CONSTRAINT feature_version_feature_id_version_key UNIQUE (feature_id, version);


--
-- Name: feature_version feature_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_version
    ADD CONSTRAINT feature_version_pkey PRIMARY KEY (id);


--
-- Name: idempotency_record idempotency_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_record
    ADD CONSTRAINT idempotency_record_pkey PRIMARY KEY (id);


--
-- Name: idempotency_record idempotency_record_tenant_id_scope_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_record
    ADD CONSTRAINT idempotency_record_tenant_id_scope_idempotency_key_key UNIQUE (tenant_id, scope, idempotency_key);


--
-- Name: job job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job
    ADD CONSTRAINT job_pkey PRIMARY KEY (id);


--
-- Name: model_deployment model_deployment_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_deployment
    ADD CONSTRAINT model_deployment_code_key UNIQUE (code);


--
-- Name: model_deployment model_deployment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_deployment
    ADD CONSTRAINT model_deployment_pkey PRIMARY KEY (id);


--
-- Name: model_provider model_provider_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_provider
    ADD CONSTRAINT model_provider_code_key UNIQUE (code);


--
-- Name: model_provider model_provider_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_provider
    ADD CONSTRAINT model_provider_pkey PRIMARY KEY (id);


--
-- Name: model_route model_route_model_alias_capability_deployment_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_model_alias_capability_deployment_code_key UNIQUE (model_alias, capability, deployment_code);


--
-- Name: model_route model_route_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_pkey PRIMARY KEY (id);


--
-- Name: outbox_event outbox_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (id);


--
-- Name: project project_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project
    ADD CONSTRAINT project_pkey PRIMARY KEY (id);


--
-- Name: provider_invocation provider_invocation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_invocation
    ADD CONSTRAINT provider_invocation_pkey PRIMARY KEY (id);


--
-- Name: run_output_event run_output_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_output_event
    ADD CONSTRAINT run_output_event_pkey PRIMARY KEY (id);


--
-- Name: run_output_event run_output_event_run_id_channel_sequence_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_output_event
    ADD CONSTRAINT run_output_event_run_id_channel_sequence_key UNIQUE (run_id, channel, sequence);


--
-- Name: run_output_stream run_output_stream_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_output_stream
    ADD CONSTRAINT run_output_stream_pkey PRIMARY KEY (run_id, channel);


--
-- Name: task_asset task_asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_asset
    ADD CONSTRAINT task_asset_pkey PRIMARY KEY (task_id, asset_id, role);


--
-- Name: task task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task
    ADD CONSTRAINT task_pkey PRIMARY KEY (id);


--
-- Name: task_run_asset task_run_asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_run_asset
    ADD CONSTRAINT task_run_asset_pkey PRIMARY KEY (run_id, direction, field_key, ordinal);


--
-- Name: task_run task_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_run
    ADD CONSTRAINT task_run_pkey PRIMARY KEY (id);


--
-- Name: task_run task_run_task_id_run_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_run
    ADD CONSTRAINT task_run_task_id_run_number_key UNIQUE (task_id, run_number);


--
-- Name: asset_blob uk_asset_blob_owner_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_blob
    ADD CONSTRAINT uk_asset_blob_owner_hash UNIQUE (tenant_id, user_id, sha256, size_bytes);


--
-- Name: workspace workspace_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT workspace_code_key UNIQUE (code);


--
-- Name: workspace workspace_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT workspace_pkey PRIMARY KEY (id);


--
-- Name: idx_artifact_asset_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_artifact_asset_asset ON public.artifact_asset USING btree (asset_id);


--
-- Name: idx_artifact_parent_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_artifact_parent_version ON public.artifact USING btree (parent_artifact_id, version_number);


--
-- Name: idx_artifact_run_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_artifact_run_created ON public.artifact USING btree (run_id, created_at);


--
-- Name: idx_artifact_task_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_artifact_task_created ON public.artifact USING btree (task_id, created_at DESC);


--
-- Name: idx_asset_active_storage_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_active_storage_key ON public.asset USING btree (storage_key) WHERE (deleted_at IS NULL);


--
-- Name: idx_asset_blob_owner_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_blob_owner_status ON public.asset_blob USING btree (tenant_id, user_id, status, created_at DESC);


--
-- Name: idx_asset_blob_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_blob_reference ON public.asset USING btree (blob_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_asset_library_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_library_owner ON public.asset USING btree (tenant_id, user_id, origin, media_category, created_at DESC) WHERE ((deleted_at IS NULL) AND ((origin)::text <> 'APP_DERIVED'::text));


--
-- Name: idx_asset_original_name_lower; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_original_name_lower ON public.asset USING btree (tenant_id, user_id, lower((original_name)::text));


--
-- Name: idx_asset_owner_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_owner_created ON public.asset USING btree (tenant_id, user_id, created_at DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_document_chunk_index; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_chunk_index ON public.document_chunk USING btree (document_index_id, ordinal);


--
-- Name: idx_document_index_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_index_owner ON public.document_index USING btree (tenant_id, user_id, status, updated_at DESC);


--
-- Name: idx_feature_workspace_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_feature_workspace_status ON public.feature_definition USING btree (workspace_id, status, sort_order);


--
-- Name: idx_idempotency_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_idempotency_created ON public.idempotency_record USING btree (created_at);


--
-- Name: idx_invocation_deployment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_invocation_deployment ON public.provider_invocation USING btree (deployment_code, started_at);


--
-- Name: idx_invocation_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_invocation_run ON public.provider_invocation USING btree (run_id, started_at);


--
-- Name: idx_job_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_job_claim ON public.job USING btree (status, type, available_at, created_at);


--
-- Name: idx_job_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_job_run ON public.job USING btree (run_id);


--
-- Name: idx_model_deployment_capability; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_model_deployment_capability ON public.model_deployment USING btree (capability, enabled, selectable);


--
-- Name: idx_model_route_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_model_route_lookup ON public.model_route USING btree (model_alias, capability, enabled, priority);


--
-- Name: idx_outbox_unpublished; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_unpublished ON public.outbox_event USING btree (status, created_at) WHERE ((status)::text = 'NEW'::text);


--
-- Name: idx_project_owner_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_owner_updated ON public.project USING btree (tenant_id, user_id, updated_at DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_provider_invocation_scope_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_provider_invocation_scope_started ON public.provider_invocation USING btree (tenant_id, invocation_scope, started_at DESC);


--
-- Name: idx_run_base_artifact; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_base_artifact ON public.task_run USING btree (base_artifact_id) WHERE (base_artifact_id IS NOT NULL);


--
-- Name: idx_run_output_event_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_output_event_created ON public.run_output_event USING btree (created_at);


--
-- Name: idx_run_output_event_replay; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_output_event_replay ON public.run_output_event USING btree (run_id, id);


--
-- Name: idx_run_owner_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_owner_created ON public.task_run USING btree (tenant_id, user_id, created_at DESC);


--
-- Name: idx_run_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_status_created ON public.task_run USING btree (status, created_at);


--
-- Name: idx_run_task_number; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_task_number ON public.task_run USING btree (task_id, run_number DESC);


--
-- Name: idx_task_asset_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_asset_active ON public.task_asset USING btree (task_id, role, ordinal) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: idx_task_asset_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_asset_asset ON public.task_asset USING btree (asset_id, task_id);


--
-- Name: idx_task_owner_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_owner_updated ON public.task USING btree (tenant_id, user_id, updated_at DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_task_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_project ON public.task USING btree (project_id, updated_at DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_task_run_asset_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_run_asset_asset ON public.task_run_asset USING btree (asset_id, run_id);


--
-- Name: idx_task_run_asset_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_run_asset_run ON public.task_run_asset USING btree (run_id, ordinal);


--
-- Name: uk_invocation_provider_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_invocation_provider_request ON public.provider_invocation USING btree (provider_code, provider_request_id) WHERE (provider_request_id IS NOT NULL);


--
-- Name: artifact_asset artifact_asset_artifact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.artifact_asset
    ADD CONSTRAINT artifact_asset_artifact_id_fkey FOREIGN KEY (artifact_id) REFERENCES public.artifact(id) ON DELETE CASCADE;


--
-- Name: artifact_asset artifact_asset_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.artifact_asset
    ADD CONSTRAINT artifact_asset_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.asset(id);


--
-- Name: artifact artifact_parent_artifact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.artifact
    ADD CONSTRAINT artifact_parent_artifact_id_fkey FOREIGN KEY (parent_artifact_id) REFERENCES public.artifact(id);


--
-- Name: artifact artifact_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.artifact
    ADD CONSTRAINT artifact_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.task_run(id);


--
-- Name: artifact artifact_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.artifact
    ADD CONSTRAINT artifact_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.task(id);


--
-- Name: document_chunk document_chunk_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_chunk
    ADD CONSTRAINT document_chunk_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.asset(id);


--
-- Name: document_chunk document_chunk_document_index_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_chunk
    ADD CONSTRAINT document_chunk_document_index_id_fkey FOREIGN KEY (document_index_id) REFERENCES public.document_index(id) ON DELETE CASCADE;


--
-- Name: document_index document_index_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_index
    ADD CONSTRAINT document_index_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.asset(id);


--
-- Name: document_index document_index_vision_deployment_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_index
    ADD CONSTRAINT document_index_vision_deployment_code_fkey FOREIGN KEY (vision_deployment_code) REFERENCES public.model_deployment(code);


--
-- Name: feature_definition feature_definition_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_definition
    ADD CONSTRAINT feature_definition_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);


--
-- Name: feature_model_option feature_model_option_deployment_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_model_option
    ADD CONSTRAINT feature_model_option_deployment_code_fkey FOREIGN KEY (deployment_code) REFERENCES public.model_deployment(code);


--
-- Name: feature_model_option feature_model_option_policy_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_model_option
    ADD CONSTRAINT feature_model_option_policy_id_fkey FOREIGN KEY (policy_id) REFERENCES public.feature_model_policy(id) ON DELETE CASCADE;


--
-- Name: feature_model_policy feature_model_policy_default_deployment_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_model_policy
    ADD CONSTRAINT feature_model_policy_default_deployment_code_fkey FOREIGN KEY (default_deployment_code) REFERENCES public.model_deployment(code);


--
-- Name: feature_model_policy feature_model_policy_feature_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_model_policy
    ADD CONSTRAINT feature_model_policy_feature_code_fkey FOREIGN KEY (feature_code) REFERENCES public.feature_definition(code);


--
-- Name: feature_version feature_version_feature_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_version
    ADD CONSTRAINT feature_version_feature_id_fkey FOREIGN KEY (feature_id) REFERENCES public.feature_definition(id);


--
-- Name: asset fk_asset_blob; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset
    ADD CONSTRAINT fk_asset_blob FOREIGN KEY (blob_id) REFERENCES public.asset_blob(id);


--
-- Name: task fk_task_current_artifact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task
    ADD CONSTRAINT fk_task_current_artifact FOREIGN KEY (current_artifact_id) REFERENCES public.artifact(id);


--
-- Name: job job_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job
    ADD CONSTRAINT job_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.task_run(id);


--
-- Name: model_deployment model_deployment_provider_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_deployment
    ADD CONSTRAINT model_deployment_provider_code_fkey FOREIGN KEY (provider_code) REFERENCES public.model_provider(code);


--
-- Name: model_route model_route_deployment_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_deployment_code_fkey FOREIGN KEY (deployment_code) REFERENCES public.model_deployment(code);


--
-- Name: provider_invocation provider_invocation_deployment_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_invocation
    ADD CONSTRAINT provider_invocation_deployment_code_fkey FOREIGN KEY (deployment_code) REFERENCES public.model_deployment(code);


--
-- Name: provider_invocation provider_invocation_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_invocation
    ADD CONSTRAINT provider_invocation_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.task_run(id);


--
-- Name: run_output_event run_output_event_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_output_event
    ADD CONSTRAINT run_output_event_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.task_run(id) ON DELETE CASCADE;


--
-- Name: run_output_stream run_output_stream_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_output_stream
    ADD CONSTRAINT run_output_stream_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.task_run(id) ON DELETE CASCADE;


--
-- Name: task_asset task_asset_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_asset
    ADD CONSTRAINT task_asset_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.asset(id);


--
-- Name: task_asset task_asset_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_asset
    ADD CONSTRAINT task_asset_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.task(id) ON DELETE CASCADE;


--
-- Name: task task_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task
    ADD CONSTRAINT task_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id);


--
-- Name: task_run_asset task_run_asset_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_run_asset
    ADD CONSTRAINT task_run_asset_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.asset(id);


--
-- Name: task_run_asset task_run_asset_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_run_asset
    ADD CONSTRAINT task_run_asset_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.task_run(id) ON DELETE CASCADE;


--
-- Name: task_run task_run_base_artifact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_run
    ADD CONSTRAINT task_run_base_artifact_id_fkey FOREIGN KEY (base_artifact_id) REFERENCES public.artifact(id);


--
-- Name: task_run task_run_selected_model_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_run
    ADD CONSTRAINT task_run_selected_model_code_fkey FOREIGN KEY (selected_model_code) REFERENCES public.model_deployment(code);


--
-- Name: task_run task_run_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_run
    ADD CONSTRAINT task_run_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.task(id);


--
-- PostgreSQL database dump complete
--
