## Converter (Importer) YouTube Music Library to Yandex Music

1. Go to Google Takeout (https://takeout.google.com/)
   and export "Music library songs" from "YouTube and YouTube Music" in csv format.
2. Edit importer.main.kts file to set variables:
    1. csvPath - path to exported library on csv format
    2. token - your Yandex Music auth token
    3. userId - your Yandex Music user id (login)
3. Start command:
   `importer.main.kts`

After the script is finished you will see the report. You will have to manually add albums that were not found.
Just click on URLs.

#### Getting Yandex Music auth token

Call:

`curl --location --request POST 'https://oauth.yandex.ru/token' \
--header 'Content-Type: application/x-www-form-urlencoded' \
--data-urlencode 'grant_type=password' \
--data-urlencode 'client_id=23cabbbdc6cd418abb4b39c32c41195d' \
--data-urlencode 'client_secret=53bc75238f0c4d08a118e51fe9203300' \
--data-urlencode 'username=<yandex_username>' \
--data-urlencode 'password=<yandex_password>'`

In result you will get json with access_token field.
