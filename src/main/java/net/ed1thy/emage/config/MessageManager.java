package net.ed1thy.emage.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

public class MessageManager {

    private final ConfigManager configManager;
    private final MiniMessage miniMessage;

    private Component prefix;
    private Component usage;
    private Component noFrame;
    private Component onlyPlayers;
    private Component maxTasksReached;
    private Component cooldownActive;
    private Component autoDetectFailed;
    private Component notEmageFrame;
    private Component metadataNotFound;
    private Component gridRemoved;
    private Component cleanupFailed;
    private Component configReloaded;
    private Component smartUrlHint;
    private Component undoMismatch;
    private Component noPermission;

    private String processingStartedRaw;
    private String downloadingRaw;
    private String processingFramesRaw;
    private String successRaw;
    private String errorRaw;
    private String processErrorRaw;
    private String readErrorRaw;
    private String notEnoughFramesRaw;
    private String gifSizeLimitRaw;
    private String imageSizeLimitRaw;
    private String gifFrameLimitRaw;
    private String protectedFrameRaw;

    private String undoName;
    private String undoHover;

    private String unsupportedFormat;
    private String errorUnknownHost;
    private String errorTimeout;
    private String errorConnect;
    private String errorSsl;
    private String errorSizeLimit;
    private String errorDimensionsLimit;
    private String errorBlocked;
    private String errorUnknown;

    private Sound successSound;
    private Sound errorSound;
    private Sound startSound;

    public MessageManager(@NotNull ConfigManager configManager) {
        this.configManager = configManager;
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void load() {
        FileConfiguration config = configManager.getRawConfig();

        String rawPrefix = config.getString("messages.prefix", "<b><gradient:#F57E3A:#CB6E38:#C36220>Emage</gradient></b> <dark_gray>•</dark_gray> ");
        this.prefix = miniMessage.deserialize(rawPrefix);

        this.usage = prefix.append(miniMessage.deserialize(config.getString("messages.usage", "<red>Использование: <gray>/emage apply <url> [колонки] [строки]")));
        this.noFrame = prefix.append(miniMessage.deserialize(config.getString("messages.no-frame", "<red>Вы должны смотреть на рамку.")));
        this.onlyPlayers = prefix.append(miniMessage.deserialize(config.getString("messages.only-players", "<red>Эту команду могут использовать только игроки.")));
        this.maxTasksReached = prefix.append(miniMessage.deserialize(config.getString("messages.max-tasks-reached", "<red>Сервер в данный момент обрабатывает максимальное количество изображений. Пожалуйста, подождите.")));
        this.cooldownActive = prefix.append(miniMessage.deserialize(config.getString("messages.cooldown-active", "<red>Слишком часто! Подождите перед наложением следующего изображения.")));
        this.autoDetectFailed = prefix.append(miniMessage.deserialize(config.getString("messages.auto-detect-failed", "<red>Не удалось автоматически определить сетку рамок. Пропущенное место подсвечено.")));
        this.notEmageFrame = prefix.append(miniMessage.deserialize(config.getString("messages.not-emage-frame", "<red>Эта рамка не содержит изображение Emage.")));
        this.metadataNotFound = prefix.append(miniMessage.deserialize(config.getString("messages.metadata-not-found", "<red>Метаданные сетки не найдены в базе данных.")));
        this.gridRemoved = prefix.append(miniMessage.deserialize(config.getString("messages.grid-removed", "<green>Изображение успешно удалено.")));
        this.cleanupFailed = prefix.append(miniMessage.deserialize(config.getString("messages.cleanup-failed", "<red>Не удалось очистить файлы.")));
        this.configReloaded = prefix.append(miniMessage.deserialize(config.getString("messages.config-reloaded", "<green>Конфигурация и сообщения успешно перезагружены.")));
        this.smartUrlHint = prefix.append(miniMessage.deserialize(config.getString("messages.smart-url-hint", "<gray><i>Подсказка: Убедитесь, что используете прямую ссылку на изображение, заканчивающуюся на .png, .jpg или .gif (не ссылка на страницу Imgur/Discord!)</i>")));
        this.undoMismatch = prefix.append(miniMessage.deserialize(config.getString("messages.undo-mismatch", "<red>Вы смотрите на другое изображение, не на то, для которого была кнопка отмены.")));
        this.noPermission = prefix.append(miniMessage.deserialize(config.getString("messages.no-permission", "<red>У вас нет прав для удаления этого изображения.")));

        this.processingStartedRaw = config.getString("messages.processing-started", "<color:#fd6039>Обработка изображения для сетки <color:#f3c6bb><width>x<height></color>. Пожалуйста, подождите...");
        this.downloadingRaw = config.getString("messages.downloading", "<color:#4CABBB>Загрузка...</color>");
        this.processingFramesRaw = config.getString("messages.processing-frames", "<color:#4CABBB>Обработка кадров: <white><percent>%</white></color>");
        this.successRaw = config.getString("messages.success", "<green>Изображение успешно наложено на <color:#f3c6bb><total></color> рамок! <gray>[<red><click:run_command:'/emage remove <sync_group_id>'><hover:show_text:'<undo_hover>'><undo_name></hover></click></red>]</gray>");
        this.errorRaw = config.getString("messages.error", "<red>Произошла ошибка: <gray><error>");
        this.processErrorRaw = config.getString("messages.process-error", "<red>Не удалось обработать изображение: <gray><error>");
        this.readErrorRaw = config.getString("messages.read-error", "<red>Ошибка чтения изображения: <gray><error>");
        this.notEnoughFramesRaw = config.getString("messages.not-enough-frames", "<red>Обнаружены недостающие рамки на этой стене. Пропущенное место подсвечено.");
        this.gifSizeLimitRaw = config.getString("messages.gif-size-limit", "<red>GIF ограничены максимальным размером сетки <color:#f3c6bb><max_width>x<max_height></color>.");
        this.imageSizeLimitRaw = config.getString("messages.image-size-limit", "<red>Изображения ограничены максимальным размером сетки <color:#f3c6bb><max_width>x<max_height></color>.");
        this.gifFrameLimitRaw = config.getString("messages.gif-frame-limit", "<red>GIF ограничены максимум <color:#f3c6bb><max_frames></color> кадрами. (В предоставленной GIF: <color:#f3c6bb><frames></color>)");
        this.protectedFrameRaw = config.getString("messages.protected-frame", "<red>Эта рамка защищена. Используйте <color:#f3c6bb>/emage remove</color>, чтобы удалить её.");

        this.undoName = config.getString("messages.undo-name", "Отменить");
        this.undoHover = config.getString("messages.undo-hover", "Нажмите, чтобы удалить это изображение");

        this.unsupportedFormat = config.getString("messages.unsupported-format", "Неподдерживаемый формат изображения или повреждённый файл.");
        this.errorUnknownHost = config.getString("messages.error-unknown-host", "Не удалось найти сервер. Проверьте URL или подключение к интернету.");
        this.errorTimeout = config.getString("messages.error-timeout", "Сервер слишком долго отвечает. Возможно, он недоступен или перегружен.");
        this.errorConnect = config.getString("messages.error-connect", "Не удалось подключиться к серверу. Хост недоступен или блокирует соединение.");
        this.errorSsl = config.getString("messages.error-ssl", "Ошибка SSL/TLS. Сертификат сайта может быть недействительным.");
        this.errorSizeLimit = config.getString("messages.error-size-limit", "Превышен лимит размера загружаемого файла.");
        this.errorDimensionsLimit = config.getString("messages.error-dimensions-limit", "Разрешение изображения превышает допустимый безопасный предел.");
        this.errorBlocked = config.getString("messages.error-blocked", "Доступ к указанному IP-адресу или ресурсу заблокирован в целях безопасности.");
        this.errorUnknown = config.getString("messages.error-unknown", "Произошла неизвестная ошибка.");

        if (configManager.enableSounds) {
            try { this.successSound = Sound.sound(Key.key(configManager.soundSuccess), Sound.Source.MASTER, 0.6f, 1.0f); } catch (Exception ignored) {}
            try { this.errorSound = Sound.sound(Key.key(configManager.soundError), Sound.Source.MASTER, 0.6f, 1.0f); } catch (Exception ignored) {}
            try { this.startSound = Sound.sound(Key.key(configManager.soundStart), Sound.Source.MASTER, 0.6f, 1.5f); } catch (Exception ignored) {}
        } else {
            this.successSound = null;
            this.errorSound = null;
            this.startSound = null;
        }
    }

    private void playSafely(@NotNull Audience audience, Sound sound) {
        if (sound != null && configManager.enableSounds) {
            audience.playSound(sound);
        }
    }

    public void sendActionBar(@NotNull Audience audience, @NotNull String rawMessage) {
        if (configManager.useActionBar) {
            audience.sendActionBar(miniMessage.deserialize(rawMessage));
        }
    }

    public void sendDownloading(@NotNull Audience audience) {
        sendActionBar(audience, this.downloadingRaw);
    }

    public void sendProcessingFrames(@NotNull Audience audience, int percent) {
        sendActionBar(audience, this.processingFramesRaw.replace("<percent>", String.valueOf(percent)));
    }

    public void clearActionBar(@NotNull Audience audience) {
        sendActionBar(audience, "");
    }

    public void sendProtectedFrame(@NotNull Audience audience) { sendActionBar(audience, protectedFrameRaw); }
    public void sendSmartUrlHint(@NotNull Audience audience) { audience.sendMessage(this.smartUrlHint); }

    public void sendUsage(@NotNull Audience audience) { audience.sendMessage(this.usage); playSafely(audience, errorSound); }
    public void sendNoFrame(@NotNull Audience audience) { audience.sendMessage(this.noFrame); playSafely(audience, errorSound); }
    public void sendOnlyPlayers(@NotNull Audience audience) { audience.sendMessage(this.onlyPlayers); playSafely(audience, errorSound); }
    public void sendMaxTasksReached(@NotNull Audience audience) { audience.sendMessage(this.maxTasksReached); playSafely(audience, errorSound); }
    public void sendCooldownActive(@NotNull Audience audience) { audience.sendMessage(this.cooldownActive); playSafely(audience, errorSound); }
    public void sendAutoDetectFailed(@NotNull Audience audience) { audience.sendMessage(this.autoDetectFailed); playSafely(audience, errorSound); }
    public void sendNotEmageFrame(@NotNull Audience audience) { audience.sendMessage(this.notEmageFrame); playSafely(audience, errorSound); }
    public void sendMetadataNotFound(@NotNull Audience audience) { audience.sendMessage(this.metadataNotFound); playSafely(audience, errorSound); }
    public void sendGridRemoved(@NotNull Audience audience) { audience.sendMessage(this.gridRemoved); playSafely(audience, successSound); }
    public void sendCleanupFailed(@NotNull Audience audience) { audience.sendMessage(this.cleanupFailed); playSafely(audience, errorSound); }
    public void sendUndoMismatch(@NotNull Audience audience) { audience.sendMessage(this.undoMismatch); playSafely(audience, errorSound); }
    public void sendNoPermission(@NotNull Audience audience) { audience.sendMessage(this.noPermission); playSafely(audience, errorSound); }

    public void sendConfigReloaded(@NotNull Audience audience) {
        audience.sendMessage(this.configReloaded);
        playSafely(audience, successSound);
    }

    public void sendProcessing(@NotNull Audience audience, int width, int height) {
        String raw = processingStartedRaw
                .replace("<width>", String.valueOf(width))
                .replace("<height>", String.valueOf(height));
        audience.sendMessage(prefix.append(miniMessage.deserialize(raw)));
        playSafely(audience, startSound);
    }

    public void sendNotEnoughFrames(@NotNull Audience audience, int width, int height) {
        String raw = notEnoughFramesRaw
                .replace("<width>", String.valueOf(width))
                .replace("<height>", String.valueOf(height));
        audience.sendMessage(prefix.append(miniMessage.deserialize(raw)));
        playSafely(audience, errorSound);
    }

    public void sendGifSizeLimit(@NotNull Audience audience, int maxWidth, int maxHeight) {
        String raw = gifSizeLimitRaw
                .replace("<max_width>", String.valueOf(maxWidth))
                .replace("<max_height>", String.valueOf(maxHeight));
        audience.sendMessage(prefix.append(miniMessage.deserialize(raw)));
        playSafely(audience, errorSound);
    }

    public void sendImageSizeLimit(@NotNull Audience audience, int maxWidth, int maxHeight) {
        String raw = imageSizeLimitRaw
                .replace("<max_width>", String.valueOf(maxWidth))
                .replace("<max_height>", String.valueOf(maxHeight));
        audience.sendMessage(prefix.append(miniMessage.deserialize(raw)));
        playSafely(audience, errorSound);
    }

    public void sendGifFrameLimit(@NotNull Audience audience, int maxFrames, int frames) {
        String raw = gifFrameLimitRaw
                .replace("<max_frames>", String.valueOf(maxFrames))
                .replace("<frames>", String.valueOf(frames));
        audience.sendMessage(prefix.append(miniMessage.deserialize(raw)));
        playSafely(audience, errorSound);
    }

    public void sendSuccess(@NotNull Audience audience, int totalFrames, int syncGroupId) {
        String raw = successRaw
                .replace("<total>", String.valueOf(totalFrames))
                .replace("<sync_group_id>", String.valueOf(syncGroupId))
                .replace("<undo_name>", undoName)
                .replace("<undo_hover>", undoHover);
        audience.sendMessage(prefix.append(miniMessage.deserialize(raw)));
        playSafely(audience, successSound);
    }

    public void sendError(@NotNull Audience audience, @NotNull String errorMessage) {
        String raw = errorRaw.replace("<error>", errorMessage);
        audience.sendMessage(prefix.append(miniMessage.deserialize(raw)));
        playSafely(audience, errorSound);
    }

    public void sendProcessError(@NotNull Audience audience, @NotNull String errorMessage) {
        String raw = processErrorRaw.replace("<error>", errorMessage);
        audience.sendMessage(prefix.append(miniMessage.deserialize(raw)));
        playSafely(audience, errorSound);
    }

    public void sendReadError(@NotNull Audience audience, @NotNull String errorMessage) {
        String raw = readErrorRaw.replace("<error>", errorMessage);
        audience.sendMessage(prefix.append(miniMessage.deserialize(raw)));
        playSafely(audience, errorSound);
    }

    public String getUnsupportedFormat() {
        return unsupportedFormat;
    }

    public String getErrorUnknownHost() {
        return errorUnknownHost;
    }

    public String getErrorTimeout() {
        return errorTimeout;
    }

    public String getErrorConnect() {
        return errorConnect;
    }

    public String getErrorSsl() {
        return errorSsl;
    }

    public String getErrorSizeLimit() {
        return errorSizeLimit;
    }

    public String getErrorDimensionsLimit() {
        return errorDimensionsLimit;
    }

    public String getErrorBlocked() {
        return errorBlocked;
    }

    public String getErrorUnknown() {
        return errorUnknown;
    }
}
