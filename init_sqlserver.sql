USE CoreBancario5B;

IF OBJECT_ID('dbo.transacciones', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.transacciones (
        id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        rrn VARCHAR(12) NOT NULL,
        stan VARCHAR(6) NOT NULL,
        numero_cuenta VARCHAR(30) NOT NULL,
        monto DECIMAL(15,2) NOT NULL,
        tipo_operacion VARCHAR(20) NOT NULL,
        saldo_anterior DECIMAL(15,2) NULL,
        saldo_posterior DECIMAL(15,2) NULL,
        codigo_respuesta VARCHAR(2) NOT NULL,
        codigo_autorizacion VARCHAR(6) NULL,
        terminal_id VARCHAR(10) NULL,
        fecha_hora DATETIME2 NOT NULL DEFAULT GETDATE()
    );
    CREATE INDEX idx_tx_rrn ON dbo.transacciones(rrn);
    CREATE INDEX idx_tx_stan ON dbo.transacciones(stan);
END;

IF OBJECT_ID('dbo.cuentas', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.cuentas (
        id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        numero_cuenta VARCHAR(30) NOT NULL UNIQUE,
        pan VARCHAR(19) NOT NULL,
        titular VARCHAR(100) NOT NULL,
        saldo_disponible DECIMAL(15,2) NOT NULL,
        saldo_contable DECIMAL(15,2) NOT NULL,
        limite_diario DECIMAL(15,2) NOT NULL DEFAULT 500.00,
        retirado_hoy DECIMAL(15,2) NOT NULL DEFAULT 0.00,
        estado VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
        pin VARCHAR(10) NOT NULL DEFAULT '1234'
    );
    CREATE INDEX idx_cuenta_num ON dbo.cuentas(numero_cuenta);
    CREATE INDEX idx_cuenta_pan ON dbo.cuentas(pan);
END;

IF NOT EXISTS (SELECT 1 FROM dbo.cuentas WHERE numero_cuenta = '1001-2002-3001')
    INSERT INTO dbo.cuentas (numero_cuenta, pan, titular, saldo_disponible, saldo_contable, limite_diario, retirado_hoy, estado, pin)
    VALUES ('1001-2002-3001', '4532000000000001', 'CARLOS PEREZ', 1500.00, 1500.00, 500.00, 0.00, 'ACTIVE', '1234');

IF NOT EXISTS (SELECT 1 FROM dbo.cuentas WHERE numero_cuenta = '1001-2002-3002')
    INSERT INTO dbo.cuentas (numero_cuenta, pan, titular, saldo_disponible, saldo_contable, limite_diario, retirado_hoy, estado, pin)
    VALUES ('1001-2002-3002', '5412000000000002', 'MARIA GOMEZ', 350.00, 350.00, 300.00, 0.00, 'ACTIVE', '4321');

IF NOT EXISTS (SELECT 1 FROM dbo.cuentas WHERE numero_cuenta = '1001-2002-3003')
    INSERT INTO dbo.cuentas (numero_cuenta, pan, titular, saldo_disponible, saldo_contable, limite_diario, retirado_hoy, estado, pin)
    VALUES ('1001-2002-3003', '4000000000000003', 'JUAN LOPEZ', 500.00, 500.00, 200.00, 0.00, 'EXPIRED', '2222');

IF NOT EXISTS (SELECT 1 FROM dbo.cuentas WHERE numero_cuenta = '1001-2002-3004')
    INSERT INTO dbo.cuentas (numero_cuenta, pan, titular, saldo_disponible, saldo_contable, limite_diario, retirado_hoy, estado, pin)
    VALUES ('1001-2002-3004', '4000000000000004', 'ANA MORALES', 1000.00, 1000.00, 500.00, 0.00, 'BLOCKED', '9999');

IF NOT EXISTS (SELECT 1 FROM dbo.cuentas WHERE numero_cuenta = '1001-2002-3005')
    INSERT INTO dbo.cuentas (numero_cuenta, pan, titular, saldo_disponible, saldo_contable, limite_diario, retirado_hoy, estado, pin)
    VALUES ('1001-2002-3005', '4000000000000005', 'PEDRO RAMOS', 10.00, 10.00, 100.00, 0.00, 'ACTIVE', '1111');
