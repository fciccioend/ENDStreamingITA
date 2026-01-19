import requests
import os

# Leggi il messaggio
with open('telegram_msg.txt', 'r', encoding='utf-8') as f:
    message = f.read()

# Configurazione
TOKEN = os.getenv('TELEGRAM_TOKEN')
CHAT_ID = os.getenv('TELEGRAM_CHAT_ID')

if not TOKEN or not CHAT_ID:
    print("❌ Token o Chat ID mancanti!")
    exit(1)

# Invia a Telegram
url = f"https://api.telegram.org/bot{TOKEN}/sendMessage"
data = {
    "chat_id": CHAT_ID,
    "text": message,
    "parse_mode": "Markdown",  # IMPORTANTE per il link cliccabile
    "disable_web_page_preview": False  # True per nascondere anteprima
}

response = requests.post(url, json=data)

if response.status_code == 200:
    print("✅ Messaggio inviato al gruppo Telegram!")
else:
    print(f"❌ Errore: {response.text}")
