/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spi.function;

import com.facebook.presto.spi.type.TimeZoneKey;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

public class SqlFunctionProperties
{
    private final boolean parseDecimalLiteralAsDouble;
    private final boolean legacyRowFieldOrdinalAccessEnabled;
    private final TimeZoneKey timeZoneKey;
    private final boolean legacyTimestamp;

    private SqlFunctionProperties(
            boolean parseDecimalLiteralAsDouble,
            boolean legacyRowFieldOrdinalAccessEnabled,
            TimeZoneKey timeZoneKey,
            boolean legacyTimestamp)
    {
        this.parseDecimalLiteralAsDouble = parseDecimalLiteralAsDouble;
        this.legacyRowFieldOrdinalAccessEnabled = legacyRowFieldOrdinalAccessEnabled;
        this.timeZoneKey = requireNonNull(timeZoneKey, "timeZoneKey is null");
        this.legacyTimestamp = legacyTimestamp;
    }

    public boolean isParseDecimalLiteralAsDouble()
    {
        return parseDecimalLiteralAsDouble;
    }

    public boolean isLegacyRowFieldOrdinalAccessEnabled()
    {
        return legacyRowFieldOrdinalAccessEnabled;
    }

    public TimeZoneKey getTimeZoneKey()
    {
        return timeZoneKey;
    }

    @Deprecated
    public boolean isLegacyTimestamp()
    {
        return legacyTimestamp;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SqlFunctionProperties)) {
            return false;
        }
        SqlFunctionProperties that = (SqlFunctionProperties) o;
        return Objects.equals(parseDecimalLiteralAsDouble, that.parseDecimalLiteralAsDouble) &&
                Objects.equals(legacyRowFieldOrdinalAccessEnabled, that.legacyRowFieldOrdinalAccessEnabled) &&
                Objects.equals(timeZoneKey, that.timeZoneKey) &&
                Objects.equals(legacyTimestamp, that.legacyTimestamp);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(parseDecimalLiteralAsDouble, legacyRowFieldOrdinalAccessEnabled, timeZoneKey, legacyTimestamp);
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private boolean parseDecimalLiteralAsDouble;
        private boolean legacyRowFieldOrdinalAccessEnabled;
        private TimeZoneKey timeZoneKey;
        private boolean legacyTimestamp;

        private Builder() {}

        public Builder setParseDecimalLiteralAsDouble(boolean parseDecimalLiteralAsDouble)
        {
            this.parseDecimalLiteralAsDouble = parseDecimalLiteralAsDouble;
            return this;
        }

        public Builder setLegacyRowFieldOrdinalAccessEnabled(boolean legacyRowFieldOrdinalAccessEnabled)
        {
            this.legacyRowFieldOrdinalAccessEnabled = legacyRowFieldOrdinalAccessEnabled;
            return this;
        }

        public Builder setTimeZoneKey(TimeZoneKey timeZoneKey)
        {
            this.timeZoneKey = requireNonNull(timeZoneKey, "timeZoneKey is null");
            return this;
        }

        public Builder setLegacyTimestamp(boolean legacyTimestamp)
        {
            this.legacyTimestamp = legacyTimestamp;
            return this;
        }

        public SqlFunctionProperties build()
        {
            return new SqlFunctionProperties(parseDecimalLiteralAsDouble, legacyRowFieldOrdinalAccessEnabled, timeZoneKey, legacyTimestamp);
        }
    }
}
