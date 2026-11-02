-- Needed to create own primary key for order_lines independent of foreign keys and non-unique keys in this table
-- so that OrderLine can be an Entity instead of Embeddable as it's more conventional to use the former instead of latter
-- and we can use @OneToMany and private List<OrderLineEntity> lines = new ArrayList<>(); instead of @ElementCollection, @CollectionTable and @OrderColumn and private List<OrderLineEmbeddable> lines = new ArrayList<>();
ALTER TABLE order_lines DROP CONSTRAINT order_lines_pkey;
ALTER TABLE order_lines ADD COLUMN id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY;
ALTER TABLE order_lines ADD CONSTRAINT uq_order_lines_order_line UNIQUE (order_id, line_no);
