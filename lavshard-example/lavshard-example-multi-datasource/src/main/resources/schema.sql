CREATE TABLE IF NOT EXISTS t_order
(
    id
    BIGINT
    NOT
    NULL
    PRIMARY
    KEY,
    user_id
    VARCHAR
(
    64
) NOT NULL,
    note VARCHAR
(
    128
) NOT NULL,
    UNIQUE
(
    user_id
)
    );