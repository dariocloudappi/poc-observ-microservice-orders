-- ============================================================
-- DDL - poc-microservice-orders
-- Base de datos: Azure SQL Database / SQL Server 2019+
-- Identificadores: UNIQUEIDENTIFIER (UUID)
-- ============================================================

-- Crear base de datos (ejecutar como admin si no existe)
-- CREATE DATABASE ordersdb;
-- GO
-- USE ordersdb;
-- GO

-- ============================================================
-- Tabla: orders
-- ============================================================
CREATE TABLE orders (
    id           UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
    user_id      VARCHAR(255)     NOT NULL,
    total_amount FLOAT            NOT NULL DEFAULT 0.0,
    status       VARCHAR(50)      NOT NULL DEFAULT 'PENDING',
    created_at   DATETIME2        NOT NULL DEFAULT GETUTCDATE(),
    updated_at   DATETIME2        NOT NULL DEFAULT GETUTCDATE(),

    CONSTRAINT PK_orders PRIMARY KEY (id),
    CONSTRAINT CHK_orders_status CHECK (
        status IN ('PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED')
    )
);
GO

-- Índice para consultas por usuario (findByUserId)
CREATE NONCLUSTERED INDEX IX_orders_user_id
    ON orders (user_id);
GO

-- Índice compuesto para consultas por usuario + estado (findByUserIdAndStatus)
CREATE NONCLUSTERED INDEX IX_orders_user_id_status
    ON orders (user_id, status);
GO

-- ============================================================
-- Tabla: order_items
-- ============================================================
CREATE TABLE order_items (
    id           UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
    order_id     UNIQUEIDENTIFIER NOT NULL,
    product_id   VARCHAR(255)     NOT NULL,
    product_name VARCHAR(500)     NOT NULL,
    quantity     INT              NOT NULL,
    unit_price   FLOAT            NOT NULL,

    CONSTRAINT PK_order_items PRIMARY KEY (id),
    CONSTRAINT FK_order_items_orders
        FOREIGN KEY (order_id) REFERENCES orders (id)
        ON DELETE CASCADE,
    CONSTRAINT CHK_order_items_quantity   CHECK (quantity > 0),
    CONSTRAINT CHK_order_items_unit_price CHECK (unit_price > 0)
);
GO

-- Índice para joins desde orders a order_items
CREATE NONCLUSTERED INDEX IX_order_items_order_id
    ON order_items (order_id);
GO

-- ============================================================
-- Verificación
-- ============================================================
SELECT 'orders'      AS tabla, COUNT(*) AS registros FROM orders
UNION ALL
SELECT 'order_items' AS tabla, COUNT(*) AS registros FROM order_items;
GO
