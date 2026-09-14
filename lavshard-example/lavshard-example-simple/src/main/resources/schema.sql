CREATE TABLE IF NOT EXISTS t_order_00
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

CREATE TABLE IF NOT EXISTS t_order_01
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