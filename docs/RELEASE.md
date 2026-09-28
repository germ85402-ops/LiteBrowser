# Выпуск Svetlo

## Один раз: ключ подписи

Все версии Svetlo должны подписываться одним и тем же ключом, иначе установленное приложение не обновится.

На своём компьютере (нужны JDK с `keytool` и `gh`, авторизованный с правами администратора репозитория):

```sh
./scripts/create-release-key.sh
```

Скрипт создаёт `~/svetlo-release-key/svetlo-release.jks` со случайным паролем и записывает в секреты
GitHub Actions `SVETLO_KEYSTORE_BASE64`, `SVETLO_STORE_PASSWORD`, `SVETLO_KEY_ALIAS`, `SVETLO_KEY_PASSWORD`.
**Сохраните папку `~/svetlo-release-key` в надёжном месте** (менеджер паролей, зашифрованный диск).
Потеря ключа = невозможность выпускать обновления. Ключ и пароль никогда не коммитятся.

## Каждый выпуск

1. Поднять `versionCode` и `versionName` в `app/build.gradle.kts`.
2. Добавить раздел `## <versionName>` в `CHANGELOG.md` — он станет описанием релиза.
3. Чтобы выпустить pre-release прямо из `main`, включить `[prerelease]` в сообщение коммита с версией:

   ```sh
   git commit -m "Release Svetlo 0.3.0-rc2 [prerelease]" && git push origin main
   ```

Workflow **Release** возьмёт тег из `versionName`, проверит ключ подписи, соберёт и протестирует APK,
убедится, что подпись не debug, и опубликует GitHub Release с APK и файлом `.sha256`.
Версия с суффиксом (`-rc2`) будет опубликована как pre-release. Обычный выпуск можно запустить тегом:

   ```sh
   git tag v0.3.0 && git push origin v0.3.0
   ```

Уже существующий тег можно переопубликовать вручную: Actions → Release → Run workflow.

## Локальная release-сборка

```sh
export SVETLO_KEYSTORE=~/svetlo-release-key/svetlo-release.jks
export SVETLO_KEY_ALIAS=svetlo
export SVETLO_STORE_PASSWORD="$(cat ~/svetlo-release-key/password.txt)" SVETLO_KEY_PASSWORD="$SVETLO_STORE_PASSWORD"
./gradlew assembleRelease
```

Без этих переменных release-APK подписывается debug-ключом (удобно для тестов, но не для публикации).

> Переход с debug-подписи: APK версии 0.1/0.2 из артефактов CI подписаны debug-ключом. Первая версия
> с настоящим ключом установится только после удаления старой.
