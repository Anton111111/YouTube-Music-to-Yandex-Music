# Converter (Importer) YouTube Music Library to Yandex Music
Script tries to find artists and albums from YouTube Music backup and like it on Yandex Music.
Also the script likes all tracks from founded albums.

## Prepare to start
Before run commands you have to do the following steps:
1. Go to Google Takeout (https://takeout.google.com/)
   and export "Music library songs" from "YouTube and YouTube Music" in csv format.
2. Generate config.json file
3. Auth in Spotify
   

### Generate config.json
To generate config.json start the command:

`generate_config.main.kts <yandex_username> <yandex_password> <path_to_csv>`

path_to_csv - path to exported library on csv format

Or you can create manually file config.json from config.json.example.

### Getting Yandex Music auth token
Call:

`curl --location --request POST 'https://oauth.yandex.ru/token' \
--header 'Content-Type: application/x-www-form-urlencoded' \
--data-urlencode 'grant_type=password' \
--data-urlencode 'client_id=23cabbbdc6cd418abb4b39c32c41195d' \
--data-urlencode 'client_secret=53bc75238f0c4d08a118e51fe9203300' \
--data-urlencode 'username=<yandex_username>' \
--data-urlencode 'password=<yandex_password>'`

In result, you will get json with access_token field.

### Auth in Spotify
Read manual in `spotify_platform_oauth.sh` file.

`spotify_platform_oauth.sh`

## YouTube Music -> Yandex Music

### Import YouTube Music to Yandex Music
Just run the command:

   `importer.main.kts`

if you want like tracks from albums run the command:

`importer.main.kts -wt`

After the script is finished you will see the report. You will have to manually add albums that were not found.
Just click on URLs.

### Like the tracks from albums
You might want to like the tracks from albums by IDs.
Run the command:

   `importer.main.kts -lts "<album_ids>"`

album_ids - is comma separated album IDs from Yandex Music.

### Dislike all tracks from Yandex Music
Just run the command:

`importer.main.kts -rct`

### Add all tracks from  library albums to playlist
Just run the command:

`importer.main.kts -attp <playlist_id>`

## Yandex Music -> Spotify
Just run the command:

`importer.yandexToSpotify.main.kts`
