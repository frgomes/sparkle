define(['admin/app', 'ng-file-upload'], function (app) {

  app.controller('UploadFile', ['$scope', 'Upload', '$mdToast', '$element', 'serverConfigWhen',
  function ($scope, Upload, $mdToast, $element, serverConfigWhen) {
    $scope.$watch('files', function () {
      $scope.upload($scope.files);
    });

    function showToast(fileName, response) {
      var simpleToast =
        $mdToast.simple()
          .content('file "' + fileName + '" uploaded.  ' + response)
          .position('bottom')
          .hideDelay(3000);
      simpleToast._options.parent = $element; // sneaky, but is there an api NG?

      $mdToast.show(simpleToast);
    }

    $scope.upload = function (files) {
      if (files && files.length) {
        serverConfigWhen.then(function(serverConfig) {
          for (var i = 0; i < files.length; i++) {
            var file = files[i];
            var up =
              Upload.upload({
                url: 'http://' + parseUrl(document.documentURL).hostname + ':' + serverConfig.adminUploadPort + '/file-upload',
                fields: {'username': $scope.username},
                file: file
              });

            up.progress(function (evt) {
              var progressPercentage = parseInt(100.0 * evt.loaded / evt.total);
              console.log('progress: ' + progressPercentage + '% ' + evt.config.file.name);
            }).success(function (data, status, headers, config) {
              showToast(config.file.name, data);
              console.log('file ' + config.file.name + 'uploaded. Response: ' + data);
            });
          }
        }).otherwise(rethrow);
      }
    };
  }]);

});