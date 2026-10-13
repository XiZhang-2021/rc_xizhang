-- Each business owns a separate table with the same seven MVP fields.
-- The legacy notification_task table is intentionally left untouched.

CREATE TABLE IF NOT EXISTS advertising_notification_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id CHAR(36) COLLATE utf8mb4_bin NOT NULL,
    request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    request_data JSON NOT NULL,
    transfer_success BOOLEAN NOT NULL DEFAULT FALSE,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    UNIQUE KEY uk_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS crm_notification_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id CHAR(36) COLLATE utf8mb4_bin NOT NULL,
    request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    request_data JSON NOT NULL,
    transfer_success BOOLEAN NOT NULL DEFAULT FALSE,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    UNIQUE KEY uk_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory_notification_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id CHAR(36) COLLATE utf8mb4_bin NOT NULL,
    request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    request_data JSON NOT NULL,
    transfer_success BOOLEAN NOT NULL DEFAULT FALSE,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    UNIQUE KEY uk_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
