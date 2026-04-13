-- Script d'initialisation PostgreSQL — à exécuter UNE SEULE FOIS par un superuser (postgres)
-- avant le premier démarrage de l'application.
--
-- Usage :
--   psql -U postgres -f orbitwatch-core/src/main/resources/db/init/create_db.sql
--
-- Ce script crée l'utilisateur et la base de données pour OrbitWatch (dev & prod).
-- Les tables sont ensuite créées automatiquement par Flyway au démarrage de l'application.

-- Créer l'utilisateur applicatif (si absent)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'orbitwatch') THEN
        CREATE USER orbitwatch WITH PASSWORD 'orbitwatch';
    END IF;
END
$$;

-- Créer la base de données (si absente)
SELECT 'CREATE DATABASE orbitwatch OWNER orbitwatch'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orbitwatch')\gexec

-- Se connecter à la base et accorder les droits sur le schéma public
\c orbitwatch
GRANT ALL ON SCHEMA public TO orbitwatch;
ALTER DATABASE orbitwatch OWNER TO orbitwatch;

