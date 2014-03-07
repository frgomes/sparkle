/* Copyright 2013  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */

define(["jslib/when/when"], function(when) {

  /** returns an object that supports the async data() api
   *
   * Call with a local array of data: [Date, Number] */
  return function(dataArray) {

    /* return data points within the domain from a local RAM array.
     * (see data.js data api)
     */
    return function(setName, column, params) {
      var domain = params.domain,
          first,
          extra = params.edgeExtra;

      dataArray.every( function(datum, index) {
        var dataTime = datum[0];
        if (dataTime < domain[0]) {
          return true;
        } else {
          first = index;
          return false;
        }
      });
      var start = first ? (extra ? first - 1 : first) : 0;

      var last = 0;
      for (var i = dataArray.length - 1; i >= start; i--) {
        var dataTime = dataArray[i][0];
        if (dataTime <= domain[1]) {
          last = i;
          break;
        }
      }
      var endDex = (last + 1 < dataArray.length) ? (extra ? last + 1 : last) : last;

      var result = dataArray.slice(start, endDex + 1);
      return when.resolve(result);
    }

  };
});
