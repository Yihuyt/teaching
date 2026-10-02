-- 智能教学平台数据库结构。MySQL 容器第一次初始化数据目录时自动执行;表结构只以本文件为准。

CREATE TABLE `announcement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned DEFAULT NULL,
  `owner_id` bigint unsigned NOT NULL,
  `title` varchar(255) NOT NULL,
  `content_markdown` mediumtext NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_announcement_owner` (`owner_id`),
  KEY `idx_announcement_course_created` (`course_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `blockcoding_chat_message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `seq` int unsigned NOT NULL,
  `role` varchar(16) NOT NULL,
  `content` mediumtext NOT NULL,
  `scripts_json` json DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blockcoding_chat_message_seq` (`session_id`,`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `blockcoding_chat_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_blockcoding_chat_session_project` (`project_id`),
  KEY `idx_blockcoding_chat_session_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `blockcoding_project` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `owner_account_id` bigint unsigned NOT NULL,
  `name` varchar(100) NOT NULL,
  `oss_object_key` varchar(255) DEFAULT NULL,
  `file_size` bigint unsigned DEFAULT NULL,
  `file_updated_at` timestamp(6) NULL DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_blockcoding_project_course_owner` (`course_id`,`owner_account_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `owner_id` bigint unsigned NOT NULL,
  `join_code` char(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `title` varchar(128) NOT NULL,
  `description_markdown` mediumtext NOT NULL,
  `published` tinyint(1) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_join_code` (`join_code`),
  KEY `idx_course_owner` (`owner_id`),
  KEY `idx_course_published_updated` (`published`,`updated_at`),
  CONSTRAINT `ck_course_join_code` CHECK (regexp_like(`join_code`,_ascii'^[0-9A-F]{10}$')),
  CONSTRAINT `ck_course_published` CHECK ((`published` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course_blockcoding_config` (
  `course_id` bigint unsigned NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '0',
  `tutor_prompt` mediumtext NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course_material` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `parent_id` bigint unsigned DEFAULT NULL,
  `parent_scope` bigint unsigned NOT NULL,
  `name` varchar(255) NOT NULL,
  `kind` varchar(16) NOT NULL,
  `object_key` varchar(512) DEFAULT NULL,
  `content_type` varchar(128) DEFAULT NULL,
  `size_bytes` bigint unsigned DEFAULT NULL,
  `sha256` char(64) DEFAULT NULL,
  `state` varchar(24) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_material_name` (`course_id`,`parent_scope`,`name`),
  UNIQUE KEY `uk_course_material_object_key` (`object_key`),
  KEY `idx_course_material_parent` (`course_id`,`parent_id`,`name`),
  KEY `idx_course_material_state` (`state`,`created_at`),
  CONSTRAINT `ck_course_material_kind` CHECK ((`kind` in (_utf8mb4'folder',_utf8mb4'file'))),
  CONSTRAINT `ck_course_material_parent_scope` CHECK ((`parent_scope` = coalesce(`parent_id`,0))),
  CONSTRAINT `ck_course_material_state` CHECK ((`state` in (_utf8mb4'pending_upload',_utf8mb4'active'))),
  CONSTRAINT `ck_course_material_storage` CHECK ((((`kind` = _utf8mb4'folder') and (`object_key` is null) and (`content_type` is null) and (`size_bytes` is null) and (`sha256` is null)) or ((`kind` = _utf8mb4'file') and (`object_key` is not null) and (`content_type` is not null) and (`size_bytes` is not null) and (`sha256` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course_member` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `joined_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_member` (`course_id`,`account_id`),
  KEY `idx_course_member_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course_outline_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `unit_id` bigint unsigned DEFAULT NULL,
  `unit_scope` bigint unsigned NOT NULL,
  `item_type` varchar(32) NOT NULL,
  `material_id` bigint unsigned DEFAULT NULL,
  `question_id` bigint unsigned DEFAULT NULL,
  `programming_problem_id` bigint unsigned DEFAULT NULL,
  `position` int unsigned NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_outline_order` (`course_id`,`unit_scope`,`position`),
  KEY `idx_course_outline_unit` (`course_id`,`unit_id`),
  KEY `idx_course_outline_material` (`course_id`,`material_id`),
  KEY `idx_course_outline_question` (`course_id`,`question_id`),
  KEY `idx_course_outline_problem` (`course_id`,`programming_problem_id`),
  CONSTRAINT `ck_course_outline_position` CHECK ((`position` >= 1)),
  CONSTRAINT `ck_course_outline_source_count` CHECK (((((`material_id` is not null) + (`question_id` is not null)) + (`programming_problem_id` is not null)) = 1)),
  CONSTRAINT `ck_course_outline_source_type` CHECK ((((`item_type` = _utf8mb4'material') and (`material_id` is not null)) or ((`item_type` = _utf8mb4'question') and (`question_id` is not null)) or ((`item_type` = _utf8mb4'programming_problem') and (`programming_problem_id` is not null)))),
  CONSTRAINT `ck_course_outline_unit_scope` CHECK ((`unit_scope` = coalesce(`unit_id`,0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course_question` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `title` varchar(255) NOT NULL,
  `time_limit_minutes` int DEFAULT NULL,
  `allow_retake` tinyint(1) NOT NULL DEFAULT '1',
  `reveal_answers` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_course_question_course` (`course_id`,`updated_at`),
  CONSTRAINT `ck_course_question_time_limit` CHECK (((`time_limit_minutes` is null) or (`time_limit_minutes` between 1 and 600))),
  CONSTRAINT `ck_course_question_title` CHECK ((char_length(trim(`title`)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course_question_attempt` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `question_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `started_at` timestamp(6) NOT NULL,
  `deadline_at` timestamp(6) NULL DEFAULT NULL,
  `submitted_at` timestamp(6) NULL DEFAULT NULL,
  `score` decimal(7,1) DEFAULT NULL,
  `answers_json` json DEFAULT NULL,
  `results_json` json DEFAULT NULL,
  `overdue` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_course_question_attempt_owner` (`question_id`,`account_id`,`started_at`),
  KEY `idx_course_question_attempt_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course_question_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `question_id` bigint unsigned NOT NULL,
  `position` int NOT NULL,
  `type` varchar(24) NOT NULL,
  `stem_markdown` mediumtext NOT NULL,
  `options_json` json DEFAULT NULL,
  `answer_json` json NOT NULL,
  `analysis_markdown` mediumtext NOT NULL,
  `score` decimal(5,1) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_question_item_position` (`question_id`,`position`),
  CONSTRAINT `ck_course_question_item_json_contract` CHECK ((((`type` = _utf8mb4'single_choice') and (`options_json` is not null) and (json_type(`options_json`) = _utf8mb4'ARRAY') and (json_length(`options_json`) >= 2) and (json_type(`answer_json`) = _utf8mb4'STRING')) or ((`type` = _utf8mb4'fill_in_blank') and (`options_json` is null) and (json_type(`answer_json`) = _utf8mb4'STRING')) or ((`type` = _utf8mb4'true_false') and (`options_json` is null) and (json_type(`answer_json`) = _utf8mb4'BOOLEAN')))),
  CONSTRAINT `ck_course_question_item_score` CHECK (((`score` > 0) and (`score` <= 1000))),
  CONSTRAINT `ck_course_question_item_type` CHECK ((`type` in (_utf8mb4'single_choice',_utf8mb4'fill_in_blank',_utf8mb4'true_false')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course_unit` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `parent_id` bigint unsigned DEFAULT NULL,
  `parent_scope` bigint unsigned NOT NULL,
  `title` varchar(128) NOT NULL,
  `position` int unsigned NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_unit_order` (`course_id`,`parent_scope`,`position`),
  KEY `idx_course_unit_tree` (`course_id`,`parent_id`,`position`),
  CONSTRAINT `ck_course_unit_parent_scope` CHECK ((`parent_scope` = coalesce(`parent_id`,0))),
  CONSTRAINT `ck_course_unit_position` CHECK ((`position` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `courseware` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `title` varchar(255) NOT NULL,
  `scene_count` int unsigned NOT NULL,
  `body` json NOT NULL,
  `version` bigint unsigned NOT NULL DEFAULT '0',
  `published` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_courseware_course` (`course_id`,`updated_at`),
  CONSTRAINT `ck_courseware_title` CHECK ((char_length(trim(`title`)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `courseware_material_bundle` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `courseware_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `name` varchar(255) NOT NULL DEFAULT '',
  `state` varchar(16) NOT NULL,
  `text` mediumtext NOT NULL,
  `images_json` json NOT NULL,
  `sources_json` json NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_courseware_material_bundle_course` (`course_id`,`created_at`),
  KEY `idx_courseware_material_bundle_created` (`created_at`),
  KEY `idx_courseware_material_bundle_account` (`account_id`),
  KEY `idx_courseware_material_bundle_courseware` (`courseware_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `courseware_qa_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `courseware_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `scene_id` varchar(32) NOT NULL,
  `question` text NOT NULL,
  `answer` text NOT NULL,
  `asked_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_courseware_qa_event_report` (`courseware_id`,`asked_at`),
  KEY `idx_courseware_qa_event_account` (`courseware_id`,`account_id`,`asked_at`),
  CONSTRAINT `ck_courseware_qa_event_question` CHECK ((char_length(trim(`question`)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `courseware_quiz_attempt` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `courseware_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `scene_id` varchar(32) NOT NULL,
  `block_id` varchar(64) NOT NULL,
  `chosen` json NOT NULL,
  `correct` tinyint(1) NOT NULL,
  `attempted_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_courseware_quiz_attempt_report` (`courseware_id`,`scene_id`,`attempted_at`),
  KEY `idx_courseware_quiz_attempt_account` (`courseware_id`,`account_id`,`attempted_at`),
  CONSTRAINT `ck_courseware_quiz_attempt_chosen` CHECK ((json_type(`chosen`) = _utf8mb4'ARRAY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `courseware_scene_view` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `courseware_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `scene_id` varchar(32) NOT NULL,
  `viewed_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_courseware_scene_view_report` (`courseware_id`,`account_id`,`viewed_at`),
  KEY `idx_courseware_scene_view_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `judge_job` (
  `id` char(36) NOT NULL,
  `submission_id` bigint unsigned NOT NULL,
  `attempt` int unsigned NOT NULL,
  `requeue_count` int unsigned NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL,
  `stream_record_id` varchar(64) DEFAULT NULL,
  `published_at` timestamp(6) NULL DEFAULT NULL,
  `completed_at` timestamp(6) NULL DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_judge_job_submission` (`submission_id`),
  KEY `idx_judge_job_status_created` (`status`,`created_at`),
  KEY `idx_judge_job_status_published` (`status`,`published_at`),
  CONSTRAINT `ck_judge_job_attempt` CHECK ((`attempt` >= 1)),
  CONSTRAINT `ck_judge_job_lifecycle` CHECK ((((`status` = _utf8mb4'pending') and (`stream_record_id` is null) and (`published_at` is null) and (`completed_at` is null)) or ((`status` = _utf8mb4'published') and (`stream_record_id` is not null) and (`published_at` is not null) and (`completed_at` is null)) or ((`status` = _utf8mb4'completed') and (`completed_at` is not null) and (((`stream_record_id` is null) and (`published_at` is null)) or ((`stream_record_id` is not null) and (`published_at` is not null)))))),
  CONSTRAINT `ck_judge_job_requeue` CHECK ((`requeue_count` <= 10)),
  CONSTRAINT `ck_judge_job_status` CHECK ((`status` in (_utf8mb4'pending',_utf8mb4'published',_utf8mb4'completed')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `judge_result_dead_letter` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `stream_record_id` varchar(64) NOT NULL,
  `job_id` varchar(64) DEFAULT NULL,
  `payload` mediumtext NOT NULL,
  `error_type` varchar(128) NOT NULL,
  `error_message` varchar(2000) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_judge_dead_letter_record` (`stream_record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `knowledge_base` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `name` varchar(100) NOT NULL,
  `active_signature` char(8) DEFAULT NULL,
  `active_index_name` varchar(64) DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_base_course_name` (`course_id`,`name`),
  CONSTRAINT `ck_knowledge_base_name` CHECK ((char_length(trim(`name`)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `knowledge_base_chunk` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `document_id` bigint unsigned NOT NULL,
  `seq` int unsigned NOT NULL,
  `section` varchar(255) NOT NULL DEFAULT '',
  `content` mediumtext NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_chunk_doc_seq` (`document_id`,`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `knowledge_base_document` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `knowledge_base_id` bigint unsigned NOT NULL,
  `material_id` bigint unsigned DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `state` varchar(16) NOT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `chunk_count` int unsigned NOT NULL DEFAULT '0',
  `signature` char(8) DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `run_token` varchar(36) DEFAULT NULL,
  `progress_heartbeat_at` timestamp(6) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kb_document_kb` (`knowledge_base_id`,`updated_at`),
  KEY `idx_kb_document_material` (`material_id`),
  CONSTRAINT `ck_kb_document_name` CHECK ((char_length(trim(`name`)) > 0)),
  CONSTRAINT `ck_kb_document_state` CHECK ((`state` in (_utf8mb4'pending',_utf8mb4'parsing',_utf8mb4'indexing',_utf8mb4'ready',_utf8mb4'error')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `knowledge_edge` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `graph_id` bigint unsigned NOT NULL,
  `source_node_id` bigint unsigned NOT NULL,
  `target_node_id` bigint unsigned NOT NULL,
  `kind` varchar(16) NOT NULL,
  `evidence` varchar(500) DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_edge_pair` (`graph_id`,`source_node_id`,`target_node_id`,`kind`),
  KEY `idx_knowledge_edge_target` (`graph_id`,`target_node_id`),
  CONSTRAINT `ck_knowledge_edge_kind` CHECK ((`kind` in (_utf8mb4'prerequisite',_utf8mb4'related'))),
  CONSTRAINT `ck_knowledge_edge_no_loop` CHECK ((`source_node_id` <> `target_node_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `knowledge_graph` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `name` varchar(128) NOT NULL,
  `published` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_graph_name` (`course_id`,`name`),
  KEY `idx_knowledge_graph_course_updated` (`course_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `knowledge_graph_build` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `material_id` bigint unsigned DEFAULT NULL,
  `material_name` varchar(255) NOT NULL,
  `status` varchar(16) NOT NULL,
  `run_token` varchar(36) DEFAULT NULL,
  `progress_heartbeat_at` timestamp(6) NULL DEFAULT NULL,
  `cancel_requested` tinyint(1) NOT NULL,
  `page_markdown_json` longtext,
  `toc_draft_json` mediumtext,
  `toc_confirmed_json` mediumtext,
  `preview_json` mediumtext,
  `mineru_tasks_json` text,
  `error_message` varchar(1000) DEFAULT NULL,
  `created_by` bigint unsigned NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kg_build_course` (`course_id`,`updated_at`),
  KEY `idx_kg_build_material` (`material_id`),
  CONSTRAINT `ck_kg_build_material_name` CHECK ((char_length(trim(`material_name`)) > 0)),
  CONSTRAINT `ck_kg_build_status` CHECK ((`status` in (_utf8mb4'parsing',_utf8mb4'toc_ready',_utf8mb4'extracting',_utf8mb4'extracted',_utf8mb4'failed')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `knowledge_graph_build_section` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `build_id` bigint unsigned NOT NULL,
  `section_index` int unsigned NOT NULL,
  `entry_index` int unsigned NOT NULL,
  `number` varchar(64) NOT NULL DEFAULT '',
  `title` varchar(255) NOT NULL,
  `path` varchar(1000) NOT NULL,
  `start_page` int unsigned NOT NULL,
  `end_page` int unsigned NOT NULL,
  `status` varchar(16) NOT NULL,
  `summary_json` mediumtext,
  `result_json` mediumtext,
  `error_message` varchar(1000) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kg_build_section` (`build_id`,`section_index`),
  CONSTRAINT `ck_kg_build_section_pages` CHECK ((`end_page` >= `start_page`)),
  CONSTRAINT `ck_kg_build_section_status` CHECK ((`status` in (_utf8mb4'pending',_utf8mb4'running',_utf8mb4'done',_utf8mb4'failed',_utf8mb4'ignored')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `knowledge_node` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `graph_id` bigint unsigned NOT NULL,
  `parent_id` bigint unsigned DEFAULT NULL,
  `parent_scope` bigint unsigned GENERATED ALWAYS AS (coalesce(`parent_id`,0)) STORED,
  `position` int unsigned NOT NULL,
  `kind` varchar(16) NOT NULL,
  `kp_type` varchar(8) DEFAULT NULL,
  `label` varchar(255) NOT NULL,
  `summary` text,
  `definition` text,
  `explanation` text,
  `aliases` json DEFAULT NULL,
  `code` text,
  `language` varchar(32) DEFAULT NULL,
  `source_section_title` varchar(255) DEFAULT NULL,
  `quote` varchar(500) DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_node_position` (`graph_id`,`parent_scope`,`position`),
  KEY `idx_knowledge_node_parent` (`graph_id`,`parent_id`),
  KEY `idx_knowledge_node_kind` (`graph_id`,`kind`),
  CONSTRAINT `ck_knowledge_node_aliases` CHECK (((`aliases` is null) or ((`kind` = _utf8mb4'knowledge_point') and (json_type(`aliases`) = _utf8mb4'ARRAY')))),
  CONSTRAINT `ck_knowledge_node_code` CHECK ((((`kind` = _utf8mb4'code_example') and (`code` is not null) and (`language` is not null)) or ((`kind` <> _utf8mb4'code_example') and (`code` is null) and (`language` is null)))),
  CONSTRAINT `ck_knowledge_node_definition` CHECK (((`definition` is null) or (`kind` = _utf8mb4'knowledge_point'))),
  CONSTRAINT `ck_knowledge_node_explanation` CHECK (((`explanation` is null) or (`kind` = _utf8mb4'code_example'))),
  CONSTRAINT `ck_knowledge_node_kind` CHECK ((`kind` in (_utf8mb4'unit',_utf8mb4'knowledge_point',_utf8mb4'code_example'))),
  CONSTRAINT `ck_knowledge_node_kp_type` CHECK ((((`kind` = _utf8mb4'knowledge_point') and (`kp_type` in (_utf8mb4'概念',_utf8mb4'方法',_utf8mb4'技能',_utf8mb4'规则',_utf8mb4'工具',_utf8mb4'易错点'))) or ((`kind` <> _utf8mb4'knowledge_point') and (`kp_type` is null)))),
  CONSTRAINT `ck_knowledge_node_label` CHECK ((char_length(trim(`label`)) > 0)),
  CONSTRAINT `ck_knowledge_node_position` CHECK ((`position` >= 1)),
  CONSTRAINT `ck_knowledge_node_summary` CHECK (((`summary` is null) or (`kind` = _utf8mb4'unit')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `knowledge_node_resource` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `graph_id` bigint unsigned NOT NULL,
  `node_id` bigint unsigned NOT NULL,
  `item_type` varchar(32) NOT NULL,
  `content_id` bigint unsigned NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_node_resource` (`node_id`,`item_type`,`content_id`),
  KEY `idx_knowledge_node_resource_content` (`item_type`,`content_id`),
  KEY `idx_knowledge_node_resource_graph` (`graph_id`),
  CONSTRAINT `ck_knowledge_node_resource_type` CHECK ((`item_type` in (_utf8mb4'material',_utf8mb4'question',_utf8mb4'programming_problem')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `learning_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `event_type` varchar(40) NOT NULL,
  `object_type` varchar(24) NOT NULL,
  `object_id` bigint unsigned NOT NULL,
  `detail` json DEFAULT NULL,
  `occurred_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_learning_event_course_account` (`course_id`,`account_id`,`occurred_at`),
  KEY `idx_learning_event_course_type_object` (`course_id`,`event_type`,`object_id`),
  KEY `idx_learning_event_course_time` (`course_id`,`occurred_at`),
  KEY `idx_learning_event_account` (`account_id`),
  CONSTRAINT `ck_learning_event_detail` CHECK (((`detail` is null) or (json_type(`detail`) = _utf8mb4'OBJECT'))),
  CONSTRAINT `ck_learning_event_object_type` CHECK ((`object_type` in (_utf8mb4'material',_utf8mb4'question',_utf8mb4'courseware',_utf8mb4'knowledge_base',_utf8mb4'knowledge_graph',_utf8mb4'programming_problem',_utf8mb4'tutor_session'))),
  CONSTRAINT `ck_learning_event_type` CHECK ((`event_type` in (_utf8mb4'material_opened',_utf8mb4'question_attempted',_utf8mb4'courseware_scene_viewed',_utf8mb4'courseware_quiz_attempted',_utf8mb4'courseware_question_asked',_utf8mb4'kb_question_asked',_utf8mb4'kg_viewed',_utf8mb4'kg_resource_opened',_utf8mb4'programming_judged',_utf8mb4'tutor_question_asked')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `object_deletion_job` (
  `id` char(36) NOT NULL,
  `bucket_name` varchar(128) NOT NULL,
  `object_key` varchar(512) NOT NULL,
  `status` varchar(16) NOT NULL,
  `attempts` int unsigned NOT NULL,
  `last_error` varchar(1000) DEFAULT NULL,
  `next_attempt_at` timestamp(6) NULL DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  `completed_at` timestamp(6) NULL DEFAULT NULL,
  `failed_at` timestamp(6) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_object_deletion_pending` (`status`,`next_attempt_at`),
  CONSTRAINT `ck_object_deletion_attempts` CHECK ((`attempts` between 0 and 5)),
  CONSTRAINT `ck_object_deletion_completion` CHECK ((((`status` = _utf8mb4'pending') and (`next_attempt_at` is not null) and (`completed_at` is null) and (`failed_at` is null)) or ((`status` = _utf8mb4'completed') and (`next_attempt_at` is null) and (`completed_at` is not null) and (`failed_at` is null) and (`last_error` is null)) or ((`status` = _utf8mb4'failed') and (`attempts` = 5) and (`next_attempt_at` is null) and (`completed_at` is null) and (`failed_at` is not null) and (`last_error` is not null)))),
  CONSTRAINT `ck_object_deletion_status` CHECK ((`status` in (_utf8mb4'pending',_utf8mb4'completed',_utf8mb4'failed')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `platform_setting` (
  `id` bigint unsigned NOT NULL,
  `site_name` varchar(64) NOT NULL,
  `footer_text` varchar(255) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_platform_setting_singleton` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `programming_problem` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `owner_id` bigint unsigned NOT NULL,
  `course_id` bigint unsigned NOT NULL,
  `title` varchar(255) NOT NULL,
  `statement_markdown` mediumtext NOT NULL,
  `difficulty` varchar(16) NOT NULL,
  `time_limit_ms` int unsigned NOT NULL,
  `memory_limit_mb` int unsigned NOT NULL,
  `output_limit_kb` int unsigned NOT NULL,
  `languages_json` json NOT NULL,
  `testcase_sha256` char(64) DEFAULT NULL,
  `testcase_size_bytes` bigint unsigned DEFAULT NULL,
  `testcase_confirmed_at` timestamp(6) NULL DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_programming_problem_owner` (`owner_id`),
  KEY `idx_programming_problem_course` (`course_id`,`updated_at`,`id`),
  CONSTRAINT `ck_programming_problem_difficulty` CHECK ((`difficulty` in (_utf8mb4'easy',_utf8mb4'medium',_utf8mb4'hard'))),
  CONSTRAINT `ck_programming_problem_memory` CHECK ((`memory_limit_mb` between 16 and 2048)),
  CONSTRAINT `ck_programming_problem_output` CHECK ((`output_limit_kb` between 1 and 65536)),
  CONSTRAINT `ck_programming_problem_testcase` CHECK ((((`testcase_sha256` is null) and (`testcase_size_bytes` is null) and (`testcase_confirmed_at` is null)) or (regexp_like(`testcase_sha256`,_utf8mb4'^[0-9a-f]{64}$') and (`testcase_size_bytes` between 1 and 268435456) and (`testcase_confirmed_at` is not null)))),
  CONSTRAINT `ck_programming_problem_time` CHECK ((`time_limit_ms` between 100 and 30000))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `programming_problem_provenance` (
  `problem_id` bigint unsigned NOT NULL,
  `problem_format_version` varchar(32) NOT NULL,
  `package_uuid` char(36) NOT NULL,
  `package_version` varchar(64) DEFAULT NULL,
  `sources_json` json DEFAULT NULL,
  `credits_json` json DEFAULT NULL,
  `license_code` varchar(32) NOT NULL,
  `rights_owner` varchar(255) DEFAULT NULL,
  `source_package_sha256` char(64) NOT NULL,
  `source_package_size_bytes` bigint unsigned NOT NULL,
  `source_package_object_key` varchar(512) NOT NULL,
  `imported_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`problem_id`),
  UNIQUE KEY `uk_programming_problem_package_uuid` (`package_uuid`),
  UNIQUE KEY `uk_programming_problem_source_object` (`source_package_object_key`),
  CONSTRAINT `ck_programming_problem_license` CHECK ((`license_code` in (_utf8mb4'unknown',_utf8mb4'public domain',_utf8mb4'cc0',_utf8mb4'cc by',_utf8mb4'cc by-sa',_utf8mb4'educational',_utf8mb4'permission'))),
  CONSTRAINT `ck_programming_problem_package_uuid` CHECK (regexp_like(`package_uuid`,_utf8mb4'^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')),
  CONSTRAINT `ck_programming_problem_source_package` CHECK ((regexp_like(`source_package_sha256`,_utf8mb4'^[0-9a-f]{64}$') and (`source_package_size_bytes` between 1 and 268435456) and (`source_package_object_key` = concat(_utf8mb4'problem-packages/',`problem_id`,_utf8mb4'/',`source_package_sha256`,_utf8mb4'.zip'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `programming_problem_sample` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `problem_id` bigint unsigned NOT NULL,
  `position` int unsigned NOT NULL,
  `input_text` mediumtext NOT NULL,
  `output_text` mediumtext NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_programming_problem_sample_position` (`problem_id`,`position`),
  CONSTRAINT `ck_programming_problem_sample_position` CHECK ((`position` between 1 and 50)),
  CONSTRAINT `ck_programming_problem_sample_size` CHECK (((length(`input_text`) <= 1048576) and (length(`output_text`) <= 1048576)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `programming_submission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `problem_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `language` varchar(24) NOT NULL,
  `source_code` mediumtext NOT NULL,
  `status` varchar(40) NOT NULL,
  `time_used_ms` int unsigned DEFAULT NULL,
  `memory_used_kb` int unsigned DEFAULT NULL,
  `score` decimal(8,2) DEFAULT NULL,
  `result_detail` varchar(2000) DEFAULT NULL,
  `submitted_at` timestamp(6) NOT NULL,
  `completed_at` timestamp(6) NULL DEFAULT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_programming_submission_account` (`account_id`,`submitted_at`),
  KEY `idx_programming_submission_problem` (`problem_id`,`status`,`submitted_at`),
  CONSTRAINT `ck_programming_submission_language` CHECK ((`language` in (_utf8mb4'C17',_utf8mb4'CPP20',_utf8mb4'PYTHON312'))),
  CONSTRAINT `ck_programming_submission_result` CHECK ((((`status` = _utf8mb4'QUEUED') and (`time_used_ms` is null) and (`memory_used_kb` is null) and (`score` is null) and (`result_detail` is null) and (`completed_at` is null)) or ((`status` <> _utf8mb4'QUEUED') and (`result_detail` is not null) and (`completed_at` is not null) and (`completed_at` >= `submitted_at`)))),
  CONSTRAINT `ck_programming_submission_score` CHECK (((`score` is null) or (`score` between 0 and 100))),
  CONSTRAINT `ck_programming_submission_status` CHECK ((`status` in (_utf8mb4'QUEUED',_utf8mb4'ACCEPTED',_utf8mb4'WRONG_ANSWER',_utf8mb4'COMPILE_ERROR',_utf8mb4'RUNTIME_ERROR',_utf8mb4'TIME_LIMIT_EXCEEDED',_utf8mb4'MEMORY_LIMIT_EXCEEDED',_utf8mb4'OUTPUT_LIMIT_EXCEEDED',_utf8mb4'SYSTEM_ERROR',_utf8mb4'WORKER_CRASH_LIMIT')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `submission_case_result` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `submission_id` bigint unsigned NOT NULL,
  `case_id` varchar(128) NOT NULL,
  `status` varchar(40) NOT NULL,
  `time_used_ms` int unsigned NOT NULL,
  `memory_used_kb` int unsigned NOT NULL,
  `score` decimal(8,2) DEFAULT NULL,
  `detail` varchar(2000) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_submission_case_result` (`submission_id`,`case_id`),
  CONSTRAINT `ck_submission_case_score` CHECK (((`score` is null) or (`score` between 0 and 100))),
  CONSTRAINT `ck_submission_case_status` CHECK ((`status` in (_utf8mb4'ACCEPTED',_utf8mb4'WRONG_ANSWER',_utf8mb4'RUNTIME_ERROR',_utf8mb4'TIME_LIMIT_EXCEEDED',_utf8mb4'MEMORY_LIMIT_EXCEEDED',_utf8mb4'OUTPUT_LIMIT_EXCEEDED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tutor_assistant` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `name` varchar(60) NOT NULL,
  `description` varchar(300) NOT NULL DEFAULT '',
  `instructions` text,
  `model` varchar(100) NOT NULL DEFAULT 'qwen-plus',
  `temperature` decimal(3,2) NOT NULL DEFAULT '0.20',
  `reasoning` tinyint(1) NOT NULL DEFAULT '0',
  `max_rounds` int NOT NULL DEFAULT '8',
  `visible_to_students` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tutor_assistant_course_name` (`course_id`,`name`),
  CONSTRAINT `ck_tutor_assistant_max_rounds` CHECK ((`max_rounds` between 1 and 16)),
  CONSTRAINT `ck_tutor_assistant_model` CHECK ((char_length(trim(`model`)) > 0)),
  CONSTRAINT `ck_tutor_assistant_name` CHECK ((char_length(trim(`name`)) > 0)),
  CONSTRAINT `ck_tutor_assistant_temperature` CHECK (((`temperature` >= 0) and (`temperature` <= 2)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tutor_assistant_knowledge_base` (
  `assistant_id` bigint unsigned NOT NULL,
  `knowledge_base_id` bigint unsigned NOT NULL,
  `position` int NOT NULL,
  PRIMARY KEY (`assistant_id`,`knowledge_base_id`),
  KEY `idx_tutor_assistant_kb_kb` (`knowledge_base_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tutor_message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `role` varchar(16) NOT NULL,
  `content` mediumtext NOT NULL,
  `sources_json` json NOT NULL,
  `trace_json` json DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tutor_message_session` (`session_id`,`id`),
  CONSTRAINT `ck_tutor_message_role` CHECK ((`role` in (_utf8mb4'user',_utf8mb4'assistant')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tutor_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL,
  `assistant_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `title` varchar(100) NOT NULL DEFAULT '新对话',
  `compressed_summary` mediumtext NOT NULL,
  `summary_up_to_msg_id` bigint unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tutor_session_owner` (`course_id`,`account_id`,`updated_at`),
  KEY `idx_tutor_session_course` (`course_id`,`updated_at`),
  KEY `idx_tutor_session_account` (`account_id`),
  KEY `idx_tutor_session_assistant` (`assistant_id`,`account_id`,`updated_at`),
  CONSTRAINT `ck_tutor_session_title` CHECK ((char_length(trim(`title`)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `username` varchar(32) NOT NULL,
  `password_hash` varchar(255) DEFAULT NULL,
  `credential_state` varchar(24) NOT NULL,
  `role` varchar(16) NOT NULL,
  `display_name` varchar(64) NOT NULL,
  `enabled` tinyint(1) NOT NULL,
  `must_reset_password` tinyint(1) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_account_username` (`username`),
  UNIQUE KEY `uk_user_account_single_root` (((case when (`role` = _utf8mb4'root') then 1 else NULL end))),
  CONSTRAINT `ck_user_account_credential_state` CHECK ((`credential_state` in (_utf8mb4'reset_required',_utf8mb4'active'))),
  CONSTRAINT `ck_user_account_password` CHECK ((((`credential_state` = _utf8mb4'reset_required') and (`password_hash` is null)) or ((`credential_state` = _utf8mb4'active') and (`password_hash` is not null)))),
  CONSTRAINT `ck_user_account_role` CHECK ((`role` in (_utf8mb4'root',_utf8mb4'admin',_utf8mb4'teacher',_utf8mb4'student'))),
  CONSTRAINT `ck_user_account_username` CHECK (regexp_like(`username`,_utf8mb4'^[a-z0-9._-]{3,32}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_ai_config` (
  `user_id` bigint unsigned NOT NULL,
  `llm_api_key_cipher` varbinary(4096) DEFAULT NULL,
  `mineru_token_cipher` varbinary(4096) DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


INSERT INTO platform_setting (id, site_name, footer_text, updated_at)
VALUES (1, '智能教学平台', '', UTC_TIMESTAMP(6));
