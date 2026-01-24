package vip.fubuki.playersync.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class LocalJsonUtil {
   private static <K> Map<K, String> stringToGenericMap(String param, Function<String, K> keyParser) {
      Map<K, String> map = new HashMap<>();
      if (param != null && param.length() >= 2 && !param.equals("{}")) {
         String s1 = param.substring(param.indexOf(123) + 1, param.lastIndexOf(125)).trim();
         if (s1.isEmpty()) {
            return map;
         } else {
            for (String split : s1.split(",")) {
               String trim = split.trim();
               int equalIndex = trim.indexOf(61);
               if (equalIndex >= 0) {
                  String key = trim.substring(0, equalIndex);
                  String value = trim.substring(equalIndex + 1);
                  map.put(keyParser.apply(key), value);
               }
            }

            return map;
         }
      } else {
         return map;
      }
   }

   public static Map<String, String> StringToMap(String param) {
      return stringToGenericMap(param, Function.identity());
   }

   public static Map<Integer, String> StringToEntryMap(String param) {
      return stringToGenericMap(param, Integer::parseInt);
   }
}
