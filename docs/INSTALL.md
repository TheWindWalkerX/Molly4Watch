<div align="center">

# 📲 Molly4Watch install guide

**Phone ➜ watch in about 10 minutes. Grab a coffee ☕, it's easy.**

![step 1](https://img.shields.io/badge/1-Download-7C5CFF?style=for-the-badge) ![step 2](https://img.shields.io/badge/2-Phone-1FA2C8?style=for-the-badge) ![step 3](https://img.shields.io/badge/3-Watch-06D6A0?style=for-the-badge) ![step 4](https://img.shields.io/badge/4-Party-FFD166?style=for-the-badge)

</div>

---

## 🧺 Before you start

- [ ] 📱 Android phone with **Google Play services** (GrapheneOS: sandboxed Google Play is fine)
- [ ] ⌚ Pixel Watch (or any Wear OS 3+ watch) already paired with the phone in the **Pixel Watch** app
- [ ] 💻 A computer with **adb** ([Android platform-tools](https://developer.android.com/tools/releases/platform-tools)). Needed for the watch.
- [ ] 📶 Phone, watch and computer on the **same Wi-Fi**

> [!IMPORTANT]
> **Already using Molly?** This build can't update it (different signing key). Do the [backup dance](#-already-using-molly) first, or you'll lose your chats. 💔

---

## 1️⃣ Download 🛍️

From **[the latest release](https://github.com/TheWindWalkerX/Molly4Watch/releases/latest)**, grab all three:

| | File | For |
|:-:|---|---|
| 🟣 | `Molly4Watch-…-phone-molly.apk` | 📱 Phone |
| 🔵 | `Molly4Watch-…-phone-relay.apk` | 📱 Phone |
| 🟡 | `Molly4Watch-…-watch.apk` | ⌚ Watch |

<details>
<summary>🕵️ <b>Optional: check the files are genuine</b></summary>

```sh
sha256sum -c SHA256SUMS.txt          # every line should say OK
apksigner verify --print-certs Molly4Watch-*-watch.apk
```

The certificate SHA-256 must be:

```
98:5D:24:CF:3F:D8:E1:94:CE:CE:3C:BF:8B:5E:79:9F:66:21:5E:F4:2B:A3:B0:79:9B:75:EA:14:84:14:86:B3
```

</details>

---

## 2️⃣ Phone 📱

**Easiest:** add the [Molly4Watch F-Droid repo](https://thewindwalkerx.github.io/Molly4Watch-fdroid/) and install both apps from there (you get updates too!). **Or** open both **phone** APKs from your Downloads and tap **Install** (allow "install unknown apps" for your browser or file manager when asked).

**Or with a cable:**

```sh
adb install Molly4Watch-v0.1.0-phone-molly.apk
adb install Molly4Watch-v0.1.0-phone-relay.apk
```

Then:

1. 🟣 Open **Molly** and set it up (register, or restore your backup).
2. 🔵 Open **Molly Wear relay** once. It should say **"Molly: connected"** ✅

> [!TIP]
> "connected, **locked**" is normal while Molly is locked. Unlock Molly and tap **Refresh**. 🔓

---

## 3️⃣ Watch ⌚

### 🧙 Unlock developer mode (one time)

1. Watch **Settings → System → About → Versions**
2. Tap **Build number** 7 times 🎉 *"You are now a developer!"*
3. **Settings → Developer options →** turn on **ADB debugging** and **Wireless debugging**

### 🔗 Pair your computer

On the watch: **Wireless debugging → Pair new device**. It shows an **IP:port** and a **6-digit code**. Keep that screen open, then on the computer:

```sh
adb pair 192.168.1.42:41234 123456      # use YOUR IP:port and code
adb devices                              # the watch should be listed
```

### 🚀 Install

```sh
adb -s <watch-serial-from-adb-devices> install Molly4Watch-v0.1.0-watch.apk
```

Then turn **ADB debugging** and **Wireless debugging** back off. 🔒 Tidy!

<details>
<summary>😵 <b>Pairing says <code>protocol fault</code>?</b></summary>

- 🔁 **Each code works once.** A failed attempt burns it. Tap *Pair new device* again for a fresh code.
- 🔌 **Use wired Ethernet** on the computer if you can. Some Wi-Fi setups drop direct connections to the watch even when everything looks fine.
- 🔋 Keep the watch **on its charger** and the screen awake while pairing.
- 🧰 Use Google's **platform-tools adb**, not an old distro package.

</details>

---

## 4️⃣ Party time 🥳

1. Unlock Molly on your phone 🔓
2. Open **Molly** on your watch ⌚
3. Your chats appear, with pictures and your phone's colors 🎨

| Try this | Do this |
|---|---|
| 💬 Reply | Open a chat ➜ **Reply** ➜ talk, type or emoji ➜ **send** |
| 👈 Quote-reply | **Swipe a message left** |
| ❤️ React | **Long-press** a message ➜ pick an emoji |
| ✅ Mark read | Bottom of a chat ➜ **Mark read** |

> [!NOTE]
> After typing a reply, **press the send button** on the confirmation screen. That's the watch's own input UI, and it needs a real tap. ✋

---

## 🔄 Already using Molly?

The official Molly can't be upgraded to this build, so move your chats over like this:

1. 🗄️ Official Molly: **Settings → Chats → Chat backups** (or **Backups → On-device backups**) **→ Create backup**. Write down the passphrase! 📝
2. 📤 Copy the backup file somewhere safe (it lives in the folder you picked).
3. 🗑️ Uninstall the official Molly.
4. 🟣 Install `…-phone-molly.apk` and choose **Restore from backup** during setup.
5. 🔵 Then continue with the relay and the watch, as above.

---

## 🧯 Troubleshooting

| 😕 Watch says… | ✅ Try |
|---|---|
| **Phone not reachable** | Is the relay installed and opened once? Is the watch connected to the phone (Bluetooth)? |
| **Molly is locked** | Unlock Molly on the phone. That's the privacy feature doing its job 🔒 |
| Chats don't update | Open the relay ➜ **Send to watch now** |
| Relay says **can't connect** | Molly and the relay must both come from *this* release (same signing key) |

## 🧹 Uninstall

Remove **Molly Wear relay** from the phone and **Molly** from the watch. Your phone's Molly keeps working as normal. 👋

<div align="center">

**Enjoy chatting from your wrist! ⌚💜**

[⬅️ Back to the README](../README.md)

</div>
