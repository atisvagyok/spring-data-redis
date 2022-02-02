/*
 * Copyright 2018-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.convert.RedisCustomConversions;
import org.springframework.data.redis.hash.HashMapper;
import org.springframework.data.redis.hash.HashObjectReader;
import org.springframework.data.redis.hash.ObjectHashMapper;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Utility to provide a {@link HashMapper} for Stream object conversion.
 * <p>
 * This utility can use generic a {@link HashMapper} or adapt specifically to {@link ObjectHashMapper}'s requirement to
 * convert incoming data into byte arrays. This class can be subclassed to override template methods for specific object
 * mapping strategies.
 *
 * @author Mark Paluch
 * @since 2.2
 * @see ObjectHashMapper
 * @see #doGetHashMapper(ConversionService, Class)
 */
class StreamObjectMapper {

	private final static RedisCustomConversions customConversions = new RedisCustomConversions();
	private final static ConversionService conversionService;

	private final HashMapper<Object, Object, Object> mapper;
	private final @Nullable HashMapper<Object, Object, Object> objectHashMapper;

	static {
		DefaultConversionService cs = new DefaultConversionService();
		customConversions.registerConvertersIn(cs);
		conversionService = cs;
	}

	/**
	 * Creates a new {@link StreamObjectMapper}.
	 *
	 * @param mapper the configured {@link HashMapper}.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	StreamObjectMapper(HashMapper<?, ?, ?> mapper) {

		Assert.notNull(mapper, "HashMapper must not be null");

		this.mapper = (HashMapper) mapper;

		if (mapper instanceof ObjectHashMapper) {
			this.objectHashMapper = new BinaryObjectHashMapperAdapter((ObjectHashMapper) mapper);
		} else {
			this.objectHashMapper = null;
		}
	}

	/**
	 * Convert the given {@link Record} into a {@link MapRecord}.
	 *
	 * @param provider provider for {@link HashMapper} to apply mapping for {@link ObjectRecord}.
	 * @param source the source value.
	 * @return the converted {@link MapRecord}.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static <K, V, HK, HV> MapRecord<K, HK, HV> toMapRecord(HashMapperProvider<HK, HV> provider, Record<K, V> source) {

		if (source instanceof ObjectRecord) {

			ObjectRecord entry = ((ObjectRecord) source);

			if (entry.getValue() instanceof Map) {
				return StreamRecords.newRecord().in(source.getStream()).withId(source.getId()).ofMap((Map) entry.getValue());
			}

			return entry.toMapRecord(provider.getHashMapper(entry.getValue().getClass()));
		}

		if (source instanceof MapRecord) {
			return (MapRecord<K, HK, HV>) source;
		}

		return Record.of(((HashMapper) provider.getHashMapper(source.getClass())).toHash(source))
				.withStreamKey(source.getStream());
	}

	/**
	 * Convert the given {@link Record} into an {@link ObjectRecord}.
	 *
	 * @param source the source value.
	 * @param provider provider for {@link HashMapper} to apply mapping for {@link ObjectRecord}.
	 * @param targetType the desired target type.
	 * @return the converted {@link ObjectRecord}.
	 */
	static <K, V, HK, HV> ObjectRecord<K, V> toObjectRecord(MapRecord<K, HK, HV> source,
			HashMapperProvider<HK, HV> provider, Class<V> targetType) {
		return source.toObjectRecord(provider.getHashMapper(targetType));
	}

	/**
	 * Map a {@link List} of {@link MapRecord}s to a {@link List} of {@link ObjectRecord}. Optimizes for empty,
	 * single-element and multi-element list transformation.l
	 *
	 * @param records the {@link MapRecord} that should be mapped.
	 * @param hashMapperProvider the provider to obtain the actual {@link HashMapper} from. Must not be {@literal null}.
	 * @param targetType the requested {@link Class target type}.
	 * @return the resulting {@link List} of {@link ObjectRecord} or {@literal null} if {@code records} was
	 *         {@literal null}.
	 */
	@Nullable
	static <K, V, HK, HV> List<ObjectRecord<K, V>> toObjectRecords(@Nullable List<MapRecord<K, HK, HV>> records,
			HashMapperProvider<HK, HV> hashMapperProvider, Class<V> targetType) {

		if (records == null) {
			return null;
		}

		if (records.isEmpty()) {
			return Collections.emptyList();
		}

		if (records.size() == 1) {
			return Collections.singletonList(toObjectRecord(records.get(0), hashMapperProvider, targetType));
		}

		List<ObjectRecord<K, V>> transformed = new ArrayList<>(records.size());
		HashMapper<V, HK, HV> hashMapper = hashMapperProvider.getHashMapper(targetType);

		for (MapRecord<K, HK, HV> record : records) {
			transformed.add(record.toObjectRecord(hashMapper));
		}

		return transformed;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	final <V, HK, HV> HashMapper<V, HK, HV> getHashMapper(Class<V> targetType) {

		HashMapper hashMapper = doGetHashMapper(conversionService, targetType);

		if (hashMapper instanceof HashObjectReader) {

			return new HashMapper<V, HK, HV>() {
				@Override
				public Map<HK, HV> toHash(V object) {
					return hashMapper.toHash(object);
				}

				@Override
				public V fromHash(Map<HK, HV> hash) {
					return ((HashObjectReader<HK, HV>) hashMapper).fromHash(targetType, hash);
				}
			};
		}

		return hashMapper;
	}

	/**
	 * Returns the actual {@link HashMapper}. Can be overridden by subclasses.
	 *
	 * @param conversionService the used {@link ConversionService}.
	 * @param targetType the target type.
	 * @return obtain the {@link HashMapper} for a certain type.
	 */
	protected HashMapper<?, ?, ?> doGetHashMapper(ConversionService conversionService, Class<?> targetType) {
		return this.objectHashMapper != null ? objectHashMapper : this.mapper;
	}

	/**
	 * Check if the given type is a simple type as in
	 * {@link org.springframework.data.convert.CustomConversions#isSimpleType(Class)}.
	 *
	 * @param targetType the type to inspect. Must not be {@literal null}.
	 * @return {@literal true} if {@link Class targetType} is a simple type.
	 * @see org.springframework.data.convert.CustomConversions#isSimpleType(Class)
	 */
	boolean isSimpleType(Class<?> targetType) {
		return customConversions.isSimpleType(targetType);
	}

	/**
	 * @return used {@link ConversionService}.
	 */
	ConversionService getConversionService() {
		return conversionService;
	}

	private static class BinaryObjectHashMapperAdapter
			implements HashMapper<Object, Object, Object>, HashObjectReader<Object, Object> {

		private final ObjectHashMapper ohm;

		public BinaryObjectHashMapperAdapter(ObjectHashMapper ohm) {
			this.ohm = ohm;
		}

		@Override
		@SuppressWarnings({ "unchecked", "rawtypes" })
		public Map<Object, Object> toHash(Object object) {
			return (Map) ohm.toHash(object);
		}

		@Override
		public Object fromHash(Map<Object, Object> hash) {
			return ohm.fromHash(toMap(hash));
		}

		@Override
		public <R> R fromHash(Class<R> type, Map<Object, Object> hash) {
			return ohm.fromHash(type, toMap(hash));
		}

		private static Map<byte[], byte[]> toMap(Map<Object, Object> hash) {

			Map<byte[], byte[]> target = new LinkedHashMap<>(hash.size());

			for (Map.Entry<Object, Object> entry : hash.entrySet()) {
				target.put(toBytes(entry.getKey()), toBytes(entry.getValue()));
			}

			return target;
		}

		@Nullable
		private static byte[] toBytes(Object value) {
			return value instanceof byte[] ? (byte[]) value : conversionService.convert(value, byte[].class);
		}
	}
}
