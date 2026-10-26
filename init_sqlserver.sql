-- =====================================================================================
-- Core Bancario 5B: esquema, migración y datos semilla (SQL Server 2019/2022)
-- Idempotente: se puede ejecutar varias veces (docker compose lo ejecuta en cada arranque).
-- La aplicación NO modifica el esquema (ddl-auto: none): este script es la fuente de verdad.
-- =====================================================================================
-- Lecturas sin bloqueo (versionado de filas): las consultas no esperan a escrituras en curso.
-- Los débitos siguen serializados por el bloqueo pesimista de la cuenta (UPDLOCK) y la idempotencia
-- por la restricción única (RRN, STAN).
IF (SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = 'CoreBancario5B') = 0
    ALTER DATABASE CoreBancario5B SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;
GO

USE CoreBancario5B;
GO

-- ---------------------------------------------------------------- Tabla de cuentas
IF OBJECT_ID('dbo.cuentas', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.cuentas (
        id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        numero_cuenta VARCHAR(30) NOT NULL,
        pan VARCHAR(19) NOT NULL,
        titular VARCHAR(100) NOT NULL,
        saldo_disponible DECIMAL(15,2) NOT NULL,
        saldo_contable DECIMAL(15,2) NOT NULL,
        limite_diario DECIMAL(15,2) NOT NULL DEFAULT 500.00,
        retirado_hoy DECIMAL(15,2) NOT NULL DEFAULT 0.00,
        fecha_ultimo_retiro DATE NULL,
        estado VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
        pin_hash VARCHAR(64) NULL,
        intentos_pin_fallidos INT NOT NULL DEFAULT 0,
        banco VARCHAR(60) NULL
    );
END;
GO

-- Migración de bases creadas con la versión anterior del script
IF COL_LENGTH('dbo.cuentas', 'fecha_ultimo_retiro') IS NULL
    ALTER TABLE dbo.cuentas ADD fecha_ultimo_retiro DATE NULL;
IF COL_LENGTH('dbo.cuentas', 'pin_hash') IS NULL
    ALTER TABLE dbo.cuentas ADD pin_hash VARCHAR(64) NULL;
IF COL_LENGTH('dbo.cuentas', 'intentos_pin_fallidos') IS NULL
    ALTER TABLE dbo.cuentas ADD intentos_pin_fallidos INT NOT NULL CONSTRAINT df_cuentas_intentos DEFAULT 0;
IF COL_LENGTH('dbo.cuentas', 'banco') IS NULL
    ALTER TABLE dbo.cuentas ADD banco VARCHAR(60) NULL;
GO

-- El PIN en claro de versiones anteriores se convierte a hash SHA-256 y se elimina
IF COL_LENGTH('dbo.cuentas', 'pin') IS NOT NULL
BEGIN
    EXEC('UPDATE dbo.cuentas SET pin_hash = CONVERT(VARCHAR(64), HASHBYTES(''SHA2_256'', numero_cuenta + '':'' + pin), 2) WHERE pin_hash IS NULL');
    DECLARE @df SYSNAME = (SELECT dc.name FROM sys.default_constraints dc
                           JOIN sys.columns c ON c.default_object_id = dc.object_id
                           WHERE dc.parent_object_id = OBJECT_ID('dbo.cuentas') AND c.name = 'pin');
    IF @df IS NOT NULL EXEC('ALTER TABLE dbo.cuentas DROP CONSTRAINT ' + @df);
    EXEC('ALTER TABLE dbo.cuentas DROP COLUMN pin');
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_cuenta_num' AND object_id = OBJECT_ID('dbo.cuentas'))
    CREATE UNIQUE INDEX idx_cuenta_num ON dbo.cuentas(numero_cuenta);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_cuenta_pan' AND object_id = OBJECT_ID('dbo.cuentas'))
    CREATE INDEX idx_cuenta_pan ON dbo.cuentas(pan);
GO

-- ---------------------------------------------------------------- Tabla de transacciones
IF OBJECT_ID('dbo.transacciones', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.transacciones (
        id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        rrn VARCHAR(12) NOT NULL,
        stan VARCHAR(6) NOT NULL,
        numero_cuenta VARCHAR(30) NOT NULL,
        cuenta_destino VARCHAR(30) NULL,
        monto DECIMAL(15,2) NOT NULL,
        tipo_operacion VARCHAR(20) NOT NULL,
        saldo_anterior DECIMAL(15,2) NULL,
        saldo_posterior DECIMAL(15,2) NULL,
        codigo_respuesta VARCHAR(2) NOT NULL,
        codigo_autorizacion VARCHAR(6) NULL,
        terminal_id VARCHAR(30) NULL,
        fecha_hora DATETIME2 NOT NULL DEFAULT SYSDATETIME()
    );
END;
GO

IF COL_LENGTH('dbo.transacciones', 'cuenta_destino') IS NULL
    ALTER TABLE dbo.transacciones ADD cuenta_destino VARCHAR(30) NULL;
IF COL_LENGTH('dbo.transacciones', 'terminal_id') < 30
    ALTER TABLE dbo.transacciones ALTER COLUMN terminal_id VARCHAR(30) NULL;
GO

-- Idempotencia garantizada por la BD: un RRN/STAN se registra una sola vez.
-- Si una base antigua tuviera duplicados, se conserva el primer registro de cada par.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uk_tx_rrn_stan' AND object_id = OBJECT_ID('dbo.transacciones'))
BEGIN
    ;WITH duplicados AS (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY rrn, stan ORDER BY id) AS n FROM dbo.transacciones
    )
    DELETE FROM duplicados WHERE n > 1;
    CREATE UNIQUE INDEX uk_tx_rrn_stan ON dbo.transacciones(rrn, stan);
END;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_tx_cuenta' AND object_id = OBJECT_ID('dbo.transacciones'))
    CREATE INDEX idx_tx_cuenta ON dbo.transacciones(numero_cuenta);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_tx_rrn' AND object_id = OBJECT_ID('dbo.transacciones'))
    CREATE INDEX idx_tx_rrn ON dbo.transacciones(rrn);
GO

-- ---------------------------------------------------------------- Cuentas semilla
-- PIN de prueba entre paréntesis; se guarda solo su hash SHA-256 ('numero_cuenta:PIN').
MERGE dbo.cuentas AS destino
USING (VALUES
    ('1001-2002-3001', '4532000000000001', 'CARLOS PEREZ', 1500.00, 500.00, 'ACTIVE',  '1234', 'BANCO INDUSTRIAL'), -- (1234)
    ('1001-2002-3002', '5412000000000002', 'MARIA GOMEZ',   350.00, 300.00, 'ACTIVE',  '4321', 'G&T CONTINENTAL'),  -- (4321)
    ('1001-2002-3003', '4000000000000003', 'JUAN LOPEZ',    500.00, 200.00, 'EXPIRED', '2222', 'BANRURAL'),         -- (2222)
    ('1001-2002-3004', '4000000000000004', 'ANA MORALES',  1000.00, 500.00, 'BLOCKED', '9999', 'BAC CREDOMATIC'),   -- (9999)
    ('1001-2002-3005', '4000000000000005', 'PEDRO RAMOS',    10.00, 100.00, 'ACTIVE',  '1111', 'BANCO PROMERICA')   -- (1111)
) AS semilla (numero_cuenta, pan, titular, saldo, limite, estado, pin, banco)
ON destino.numero_cuenta = semilla.numero_cuenta
WHEN NOT MATCHED THEN
    INSERT (numero_cuenta, pan, titular, saldo_disponible, saldo_contable, limite_diario, retirado_hoy, estado, pin_hash, intentos_pin_fallidos, banco)
    VALUES (semilla.numero_cuenta, semilla.pan, semilla.titular, semilla.saldo, semilla.saldo, semilla.limite, 0.00, semilla.estado,
            CONVERT(VARCHAR(64), HASHBYTES('SHA2_256', semilla.numero_cuenta + ':' + semilla.pin), 2), 0, semilla.banco)
WHEN MATCHED AND (destino.pin_hash IS NULL OR destino.banco IS NULL) THEN
    UPDATE SET pin_hash = COALESCE(destino.pin_hash, CONVERT(VARCHAR(64), HASHBYTES('SHA2_256', semilla.numero_cuenta + ':' + semilla.pin), 2)),
               banco = COALESCE(destino.banco, semilla.banco);
GO

PRINT 'Esquema y datos de CoreBancario5B listos.';
