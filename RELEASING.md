# Релізне підписування

Релізний ключ — це «паспорт» застосунку. Хто має ключ, той може випускати
оновлення, яким телефони довірятимуть (а застосунок читає сповіщення).

* Ключ і паролі **ніколи** не потрапляють у Git.
* Дві копії: менеджер паролів + офлайн-носій. Втрата ключа = неможливо оновити
  застосунок поза Google Play (у Play діє Play App Signing, див. нижче).
* `keystore/debug.jks` — публічний, лише для debug-збірок.

## 1. Створити ключ (один раз, на своєму комп'ютері)

Потрібна Java (є в Android Studio: `<Android Studio>/jbr/bin/keytool`).

```
keytool -genkeypair -v -keystore ballistic-release.jks -alias ballistic \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Balistychna Varta, C=UA"
```

Придумайте довгий пароль і збережіть його разом із файлом у менеджері паролів.

## 2. Додати в GitHub Secrets

Repository → Settings → Secrets and variables → Actions → New repository secret:

| Secret | Значення |
|---|---|
| `BW_RELEASE_KEYSTORE_BASE64` | вивід `base64 -w0 ballistic-release.jks` (macOS: `base64 -i ballistic-release.jks`) |
| `BW_RELEASE_STORE_PASSWORD` | пароль сховища |
| `BW_RELEASE_KEY_ALIAS` | `ballistic` |
| `BW_RELEASE_KEY_PASSWORD` | пароль ключа (у keytool за замовчуванням той самий) |

## 3. Зібрати реліз

GitHub → Actions → **Release build** → Run workflow (або `git tag v0.2.0 && git push --tags`).
Результат — артефакт `KyivBallisticWatch-release` з `app-release.apk` (встановлення
напряму) та `app-release.aab` (для Google Play).

Відбиток сертифіката (SHA-256) з логу збірки запишіть сюди, щоб будь-хто міг
перевірити справжність APK:

```
SHA-256: (заповнити після першої релізної збірки)
```

## Google Play App Signing

Під час першого завантаження в Play Console погодьтеся на Play App Signing:
Google зберігає ключ підпису для магазину, а ваш ключ стає «ключем завантаження»,
який можна відновити через підтримку, якщо його втрачено.
