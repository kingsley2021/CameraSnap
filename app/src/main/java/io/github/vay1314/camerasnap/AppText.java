package io.github.vay1314.camerasnap;

import android.annotation.SuppressLint;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AppText {
    private final int resource;
    private final Object[] arguments;

    private AppText(int resource, Object... arguments) {
        this.resource = resource;
        this.arguments = arguments;
    }

    static AppText of(int resource, Object... arguments) { return new AppText(resource, arguments); }

    String render(Context context) {
        return renderLocalized(AppLanguage.wrap(context));
    }

    private String renderLocalized(Context context) {
        Object[] values = arguments.clone();
        for (int i = 0; i < values.length; i++)
            if (values[i] instanceof AppText) values[i] = ((AppText) values[i]).renderLocalized(context);
        return context.getString(resource, values);
    }

    AppText then(AppText next) {
        return next == null ? this : of(R.string.text_join, this, next);
    }

    JSONObject encode(Context context) throws JSONException {
        JSONArray values = new JSONArray();
        for (Object value : arguments)
            values.put(value instanceof AppText ? ((AppText) value).encode(context) : value);
        return new JSONObject().put("key", context.getResources().getResourceEntryName(resource)).put("args", values);
    }

    @SuppressLint("DiscouragedApi")
    static AppText decode(Context context, JSONObject data) throws JSONException {
        int resource = context.getResources().getIdentifier(data.getString("key"), "string", SnapConfig.PACKAGE);
        if (resource == 0) return of(R.string.status_unavailable);
        JSONArray values = data.getJSONArray("args");
        Object[] arguments = new Object[values.length()];
        for (int i = 0; i < arguments.length; i++) {
            Object value = values.get(i);
            arguments[i] = value instanceof JSONObject ? decode(context, (JSONObject) value) : value;
        }
        return of(resource, arguments);
    }

    static AppText error(Throwable error) {
        if (error instanceof Failure) return ((Failure) error).text;
        String detail = error.getMessage();
        return of(R.string.error_detail, error.getClass().getSimpleName(), detail == null ? "" : detail);
    }

    static Failure failure(int resource, Object... arguments) { return new Failure(of(resource, arguments)); }

    static Failure failure(Throwable cause, int resource, Object... arguments) {
        Failure failure = failure(resource, arguments);
        failure.initCause(cause);
        return failure;
    }

    static final class Failure extends IllegalStateException {
        final AppText text;
        Failure(AppText text) {
            super("CameraSnap error resource: " + text.resource);
            this.text = text;
        }
    }
}
