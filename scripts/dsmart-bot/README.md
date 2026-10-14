# D Smart GO playlist bot

`dsmartgo.com.tr` üzerindeki canlı kanal kataloğunu çeker, misafir (girişsiz)
hesapla oynatılabilen kanalların imzalı HLS linklerini üretir ve iki dosya
yazar:

| Dosya | Amaç |
| --- | --- |
| `playlists/dsmart.m3u` | Oynatıcılar için hazır playlist (`#EXTM3U`) |
| `playlists/dsmart.json` | Uygulama entegrasyonu için yapılandırılmış veri |

Ücretli / giriş gerektiren kanallar elenir; çıktı yalnızca **çalışan ücretsiz
kanalları** içerir.

## Neden zamanlanmış çalışıyor?

İmzalı linkler `&e=<epoch>` parametresiyle **yaklaşık 4 saat** geçerli.
İmzasız ham linkler `403` döner. Bu yüzden GitHub Action playlist'i **2 saatte
bir** yeniler. Manuel tetikleme de eklidir (`workflow_dispatch`).

## Akış

1. **site-config** okunur (API host'ları, `apikey`, `sec` anahtarı). MerlinCDN
   alt alan adları yayınlara göre değişebildiği için sabit değerler yedek olarak
   tutulur.
2. **Kanal kataloğu**: `POST /v1/item/filter/` → `contentTypeIds=[27819]`
   (LiveChannel) → 115 kanal (id, ad, kanal no, tür, logo, kök HLS URL'leri).
3. **Misafir oturumu**: `fasterius.ercdn.com/ues/client/get_id` →
   `merlincdn.net/create?...` (en iyi ihtimalle çalışır, zorunlu değil).
4. **İmzalama**: `POST merlincdn.net/content` + `sec` başlığı. Kanalın
   `VideoUrlConfTag` değerine göre `streamType` denenir:
   - `daion` / `daionWithNoDVR` → `daion` (+ `?app=dsmart_web` eklenir)
   - `default` / `defaultWithArchive` → `hasdvr` (1440 dk)
   - diğer / boş → `hasinitialdvr` → `hasdvr` → `hasreverseepg`
5. **Doğrulama**: her imzalı master playlist `#EXTM3U` dönene kadar çekilir.
   Başarısız olan kanal listeye girmez.

Hata kodları site konfigürasyonundaki error modelleriyle eşleşir:

| Kod | Anlamı |
| --- | --- |
| `500106` | `NEED_LOGIN` – kanal giriş istiyor → elenir |
| `500308` | `NEED_PACKAGE` – ücretli paket → elenir |
| `500301` | Yanlış `streamType` → sıradaki aday denenir |

## Yerel çalıştırma

```bash
python scripts/dsmart-bot/dsmart_bot.py --out-dir playlists
```

Sadece Python standart kütüphanesi kullanılır, `pip install` gerekmez.

Parametreler:

- `--out-dir DIR` — çıktı dizini (varsayılan `playlists`)
- `--workers N` — paralel istek sayısı (varsayılan `8`)
- `--min-channels N` — bundan az kanal bulunursa çıkış kodu `1` (varsayılan `5`)
- `--no-verify` — imzalı playlist çekme doğrulamasını atla (daha hızlı)
- `--timeout S` — istek zaman aşımı (varsayılan `30`)

## GitHub Action

`.github/workflows/dsmart-playlist.yml`:

- `17 */2 * * *` cron'u (2 saatte bir) + `workflow_dispatch`
- Değişiklik varsa `playlists/dsmart.m3u` ve `playlists/dsmart.json`
  dosyalarını `github-actions[bot]` ile commit'ler
- `permissions: contents: write` gerekir; fork'larda Actions sekmesinden
  workflow'ün etkinleştirilmiş olması gerekir

## Notlar

- MerlinCDN, `curl` benzeri TLS parmak izine sahip istemcilerden gelen
  POST isteklerine `403`/`500` döndürür. Bu yüzden istekler Python `urllib`
  ile atılır ve tarayıcı user-agent'ı kullanılır.
- Kanal adı/logo/tür verisi CMS'ten gelir; EPG için kanal `tvg-id`
  (`contentEncodedName`, örn. `trt-1`) kullanılır.
- Yayın hakları D Smart GO'ya aittir; çıktı yalnızca sitenin misafir
  kullanıcıya zaten açtığı kanalları içerir.
