CREATE TABLE IF NOT EXISTS counter (
    id    INT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL UNIQUE,
    value INT NOT NULL DEFAULT 0
);

INSERT IGNORE INTO counter (name, value) VALUES ('page_views', 0), ('api_calls', 0);
