/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common;

import java.util.function.Function;

public interface Result<SUCCESS, ERROR> {

    static <SUCCESS, ERROR> Result<SUCCESS, ERROR> success(SUCCESS value) {
        return new Success<>(value);
    }

    static <SUCCESS, ERROR> Result<SUCCESS, ERROR> error(ERROR value) {
        return new Error<>(value);
    }

    boolean isSuccess();

    boolean isError();

    SUCCESS get();

    ERROR getError();

    @SuppressWarnings("unchecked")
    default <U> Result<U, ERROR> map(Function<? super SUCCESS, ? extends U> mapper) {
        assert mapper != null : "mapper function must not be null";
        if (isError()) {
            return (Result<U, ERROR>) this;
        }

        return new Success<>(mapper.apply(get()));
    }

    record Success<SUCCESS, ERROR>(SUCCESS value) implements Result<SUCCESS, ERROR> {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public boolean isError() {
            return false;
        }

        @Override
        public SUCCESS get() {
            return value;
        }

        @Override
        public ERROR getError() {
            return null;
        }
    }

    record Error<SUCCESS, ERROR>(ERROR error) implements Result<SUCCESS, ERROR> {

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public boolean isError() {
            return true;
        }

        @Override
        public SUCCESS get() {
            throw new IllegalStateException("asdasd");
        }

        @Override
        public ERROR getError() {
            return error;
        }
    }
}
