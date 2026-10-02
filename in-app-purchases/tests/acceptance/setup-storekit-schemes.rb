require 'rexml/document'
require 'rexml/xpath'
require 'pathname'
require 'xcodeproj'

abort 'expected app scheme, test scheme, and StoreKit configuration paths' unless ARGV.length == 3
app_scheme, test_scheme, storekit_path = ARGV

[app_scheme, test_scheme].each do |scheme_path|
  document = REXML::Document.new(File.read(scheme_path))
  launch_action = REXML::XPath.first(document, '/Scheme/LaunchAction')
  abort "scheme has no LaunchAction: #{scheme_path}" unless launch_action

  launch_action.elements.to_a('StoreKitConfigurationFileReference').each do |reference|
    launch_action.delete_element(reference)
  end
  reference = launch_action.add_element('StoreKitConfigurationFileReference')
  project_directory = File.expand_path(File.dirname(File.dirname(File.dirname(scheme_path))))
  relative_storekit_path = Pathname.new(File.expand_path(storekit_path))
    .relative_path_from(Pathname.new(project_directory))
  reference.add_attribute('identifier', relative_storekit_path.to_s)

  File.write(scheme_path, document.to_s)
end

project_directory = File.expand_path(File.dirname(File.dirname(File.dirname(app_scheme))))
project = Xcodeproj::Project.open(project_directory)
relative_storekit_path = Pathname.new(File.expand_path(storekit_path))
  .relative_path_from(Pathname.new(project_directory))
unless project.files.any? { |file| file.path == relative_storekit_path.to_s }
  project.main_group.new_file(relative_storekit_path.to_s)
  project.save
end
