CREATE TABLE article_read_migration (
    id INT NOT NULL PRIMARY KEY,
    completed TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO article_read_migration(id, completed) VALUES (1, 0);
