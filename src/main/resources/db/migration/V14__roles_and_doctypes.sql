CREATE TABLE role_configs (
    key             VARCHAR(50)  PRIMARY KEY,
    label           VARCHAR(100) NOT NULL,
    permission_level INTEGER     NOT NULL,
    is_system       BOOLEAN      NOT NULL DEFAULT false,
    description     VARCHAR(255),
    sort_order      INTEGER      NOT NULL DEFAULT 0
);

INSERT INTO role_configs (key, label, permission_level, is_system, description, sort_order) VALUES
    ('PODWYKONAWCA',  'Podwykonawca',            1, true, 'Podwykonawca budowlany – dostęp do podstawowych dokumentów', 10),
    ('BRYGADZISTA',   'Brygadzista',              2, true, 'Brygadzista – dostęp do dokumentów wykonawczych',           20),
    ('INZYNIER',      'Inżynier',                 3, true, 'Inżynier nadzoru – dostęp do dokumentów technicznych',      30),
    ('KOSZTORYSANT',  'Kosztorysant',             3, true, 'Kosztorysant – dostęp do dokumentów finansowych',           31),
    ('KIEROWNIK',     'Kierownik budowy',          4, true, 'Kierownik budowy – pełny dostęp do dokumentów projektu',   40),
    ('ADMIN',         'Administrator projektu',    5, true, 'Administrator projektu – zarządzanie członkami',            50),
    ('OWNER',         'Właściciel projektu',       6, true, 'Właściciel projektu – pełna kontrola',                     60);

CREATE TABLE document_type_configs (
    key         VARCHAR(50)  PRIMARY KEY,
    label       VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    sort_order  INTEGER      NOT NULL DEFAULT 0
);

INSERT INTO document_type_configs (key, label, description, sort_order) VALUES
    ('UMOWA_INWESTOR',    'Umowa z inwestorem',    'Umowa zawarta z inwestorem projektu',         10),
    ('UMOWA_PODWYKONAWCA','Umowa z podwykonawcą',  'Umowa zawarta z podwykonawcą',                20),
    ('KOSZTORYS',         'Kosztorys',             'Kosztorys robót budowlanych',                 30),
    ('HARMONOGRAM',       'Harmonogram',           'Harmonogram realizacji prac',                 40),
    ('PROJEKT_WYKONAWCZY','Projekt wykonawczy',    'Dokumentacja wykonawcza',                     50),
    ('SWZ',               'SWZ',                  'Specyfikacja Warunków Zamówienia',             60),
    ('FAKTURA',           'Faktura',               'Faktura za wykonane prace',                   70),
    ('INNE',              'Inne',                  'Pozostałe dokumenty',                         80);
