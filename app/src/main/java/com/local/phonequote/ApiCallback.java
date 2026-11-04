package com.local.phonequote;

public interface ApiCallback<T> {
    void onSuccess(T value);

    void onError(String message);
}
