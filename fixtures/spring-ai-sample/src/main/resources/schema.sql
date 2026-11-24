create table if not exists orders (
    id          bigint auto_increment primary key,
    customer_id varchar(64) not null,
    sku         varchar(64) not null,
    quantity    int         not null
);
