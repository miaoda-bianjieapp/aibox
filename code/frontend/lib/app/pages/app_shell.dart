import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../state/app_data_controller.dart';
import 'features_page.dart';
import 'home_page.dart';
import 'profile_page.dart';

class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  int _selectedIndex = 0;
  late final AppDataController _data;

  @override
  void initState() {
    super.initState();
    _data = AppDataController()..refresh();
  }

  @override
  void dispose() {
    _data.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _selectedIndex,
        children: [
          HomePage(data: _data, onOpenFeatures: () => _selectTab(1)),
          FeaturesPage(data: _data),
          ProfilePage(data: _data),
        ],
      ),
      bottomNavigationBar: _AppBottomNavigation(
        selectedIndex: _selectedIndex,
        onSelected: _selectTab,
      ),
    );
  }

  void _selectTab(int index) {
    if (_selectedIndex == index) return;
    setState(() => _selectedIndex = index);
  }
}

class _AppBottomNavigation extends StatelessWidget {
  const _AppBottomNavigation({
    required this.selectedIndex,
    required this.onSelected,
  });

  final int selectedIndex;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) {
    const destinations = [
      (icon: Icons.home_outlined, selected: Icons.home_rounded, label: '首页'),
      (
        icon: Icons.grid_view_outlined,
        selected: Icons.grid_view_rounded,
        label: '功能'
      ),
      (
        icon: Icons.person_outline_rounded,
        selected: Icons.person_rounded,
        label: '我的'
      ),
    ];

    return DecoratedBox(
      decoration: const BoxDecoration(
        color: AppColors.paper,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: 70,
          child: Row(
            children: List.generate(destinations.length, (index) {
              final item = destinations[index];
              final selected = index == selectedIndex;
              return Expanded(
                child: InkWell(
                  onTap: () => onSelected(index),
                  child: Semantics(
                    selected: selected,
                    button: true,
                    label: item.label,
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        AnimatedContainer(
                          duration: const Duration(milliseconds: 180),
                          width: 22,
                          height: 2,
                          decoration: BoxDecoration(
                            color: selected
                                ? AppColors.accent
                                : Colors.transparent,
                            borderRadius: BorderRadius.circular(1),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Icon(
                          selected ? item.selected : item.icon,
                          size: 23,
                          color: selected ? AppColors.ink : AppColors.muted,
                        ),
                        const SizedBox(height: 3),
                        Text(
                          item.label,
                          style: TextStyle(
                            color: selected ? AppColors.ink : AppColors.muted,
                            fontSize: 11,
                            fontWeight:
                                selected ? FontWeight.w700 : FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              );
            }),
          ),
        ),
      ),
    );
  }
}
